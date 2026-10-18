package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith 모듈 경계 검증 (설계 4장, 00-IMPL Step 1).
 * 순환 의존·비공개(internal) 패키지 접근이 있으면 실패한다 → CI 차단.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(ErpServerApplication.class);

    @Test
    @DisplayName("Step1: ApplicationModules.verify() — 실제 코드베이스 모듈 경계 위반 0")
    void verifiesModularStructure() {
        modules.verify();
        assertThat(modules.stream().map(m -> m.getIdentifier().toString())).contains("core", "fac", "hrq");
        // fac 는 다른 업무 모듈의 내부 타입을 참조하지 않는다(core::api 만 허용) — verify() 가 위반 시 실패
        ApplicationModule fac = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("fac")).findFirst().orElseThrow();
        assertThat(fac.getNamedInterfaces().getByName("api")).isPresent();
        ApplicationModule core = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("core")).findFirst().orElseThrow();
        assertThat(core.getNamedInterfaces().getByName("api")).isPresent();
    }

    @Test
    @DisplayName("Step1 DoD(음성 테스트): fld 가 rpt 내부(domain) 타입을 참조하면 verify() 실패")
    void detectsBoundaryViolation() {
        ImportOption includeTests = location -> true;
        var fixture = ApplicationModules.of("com.atomeng.erp.fixture.violation", includeTests);
        assertThatThrownBy(fixture::verify)
                .hasMessageContaining("fld")
                .hasMessageContaining("com.atomeng.erp.fixture.violation.rpt.domain.Report");
    }

    @Test
    @DisplayName("Step1 DoD(양성 테스트): 허용된 named interface(rpt::api) 만 쓰면 통과")
    void allowsNamedInterfaceDependency() {
        ImportOption includeTests = location -> true;
        ApplicationModules.of("com.atomeng.erp.fixture.allowed", includeTests).verify();
    }

    @Test
    @DisplayName("Step1: 모듈 문서(PlantUML·Canvas) 생성 → build/spring-modulith-docs")
    void writesDocumentation() {
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder("build/spring-modulith-docs"))
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
