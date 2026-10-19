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
        assertThat(modules.stream().map(m -> m.getIdentifier().toString())).contains("core", "fac", "hrq", "eqp", "cst", "fcd", "sal", "rpt", "qar", "dem");
        // cst 는 core::api · hrq::api · fac::api 공개 타입만 참조(package-info allowedDependencies) — 위반 시 verify() 실패
        ApplicationModule cst = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("cst")).findFirst().orElseThrow();
        assertThat(cst.getNamedInterfaces().getByName("api")).isPresent();
        ApplicationModule hrq = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("hrq")).findFirst().orElseThrow();
        assertThat(hrq.getNamedInterfaces().getByName("api")).isPresent();
        // fac 는 다른 업무 모듈의 내부 타입을 참조하지 않는다(core::api 만 허용) — verify() 가 위반 시 실패
        ApplicationModule fac = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("fac")).findFirst().orElseThrow();
        assertThat(fac.getNamedInterfaces().getByName("api")).isPresent();
        // fcd 는 core::api · hrq::api 공개 타입만 참조(CST 와는 이벤트 계약으로만 연결) — 위반 시 verify() 실패
        ApplicationModule fcd = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("fcd")).findFirst().orElseThrow();
        assertThat(fcd.getNamedInterfaces().getByName("api")).isPresent();
        // eqp 는 core::api 만 허용(allowedDependencies) — CST 와는 eqp.equipment.returned 이벤트 계약만
        ApplicationModule eqp = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("eqp")).findFirst().orElseThrow();
        assertThat(eqp.getNamedInterfaces().getByName("api")).isPresent();
        ApplicationModule core = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("core")).findFirst().orElseThrow();
        assertThat(core.getNamedInterfaces().getByName("api")).isPresent();
    }

    @Test
    @DisplayName("TC-EQP-012: 다른 업무 모듈 → eqp 내부(api 외) 패키지 직접 참조 0건, eqp → 다른 업무 모듈(core::api 외) 참조 0건")
    void eqpBoundary() {
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.atomeng.erp");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("com.atomeng.erp.eqp..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.eqp.application..",
                        "com.atomeng.erp.eqp.domain..", "com.atomeng.erp.eqp.adapter..")
                .check(classes);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.atomeng.erp.eqp..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.fac..", "com.atomeng.erp.hrq..",
                        "com.atomeng.erp.cst..", "com.atomeng.erp.fcd..", "com.atomeng.erp.core.application..",
                        "com.atomeng.erp.core.domain..", "com.atomeng.erp.core.adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("01-IMPL: sal 은 core::api · fac::api 만 참조, 다른 업무 모듈 → sal 참조 0건(CST 는 이벤트 계약만)")
    void salBoundary() {
        ApplicationModule sal = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("sal")).findFirst().orElseThrow();
        assertThat(sal.getNamedInterfaces().getByName("api")).isPresent();
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.atomeng.erp");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.atomeng.erp.sal..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.hrq..", "com.atomeng.erp.cst..",
                        "com.atomeng.erp.fcd..", "com.atomeng.erp.eqp..", "com.atomeng.erp.core.application..",
                        "com.atomeng.erp.core.domain..", "com.atomeng.erp.core.adapter..",
                        "com.atomeng.erp.fac.application..", "com.atomeng.erp.fac.domain..", "com.atomeng.erp.fac.adapter..")
                .check(classes);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("com.atomeng.erp.sal..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.sal..")
                .check(classes);
    }

    @Test
    @DisplayName("06-IMPL Step 0: rpt 는 core::api · fac::api · eqp::api 만 참조, 다른 업무 모듈 → rpt 는 rpt::api 만(현재 0건)")
    void rptBoundary() {
        ApplicationModule rpt = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("rpt")).findFirst().orElseThrow();
        assertThat(rpt.getNamedInterfaces().getByName("api")).isPresent();
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.atomeng.erp");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.atomeng.erp.rpt..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.hrq..", "com.atomeng.erp.cst..",
                        "com.atomeng.erp.fcd..", "com.atomeng.erp.sal..", "com.atomeng.erp.core.application..",
                        "com.atomeng.erp.core.domain..", "com.atomeng.erp.core.adapter..",
                        "com.atomeng.erp.fac.application..", "com.atomeng.erp.fac.domain..", "com.atomeng.erp.fac.adapter..",
                        "com.atomeng.erp.eqp.application..", "com.atomeng.erp.eqp.domain..", "com.atomeng.erp.eqp.adapter..")
                .check(classes);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("com.atomeng.erp.rpt..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.rpt.application..",
                        "com.atomeng.erp.rpt.domain..", "com.atomeng.erp.rpt.adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("07-IMPL: qar 는 core::api · rpt::api 만 참조(RptRevisionQuery · 구독 계약), 다른 모듈 → qar 내부 0건, rpt → qar 0건(이벤트만)")
    void qarBoundary() {
        ApplicationModule qar = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("qar")).findFirst().orElseThrow();
        assertThat(qar.getNamedInterfaces().getByName("api")).isPresent();
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.atomeng.erp");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.atomeng.erp.qar..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.hrq..", "com.atomeng.erp.cst..",
                        "com.atomeng.erp.fcd..", "com.atomeng.erp.sal..", "com.atomeng.erp.fac..", "com.atomeng.erp.eqp..",
                        "com.atomeng.erp.core.application..", "com.atomeng.erp.core.domain..", "com.atomeng.erp.core.adapter..",
                        "com.atomeng.erp.rpt.application..", "com.atomeng.erp.rpt.domain..", "com.atomeng.erp.rpt.adapter..")
                .check(classes);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("com.atomeng.erp.qar..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.qar..")
                .check(classes);
    }

    @Test
    @DisplayName("10-IMPL Step 1: dem 은 core::api · fac::api · hrq::api 만 참조, 다른 업무 모듈 → dem 0건(FAC 와는 이벤트 계약만)")
    void demBoundary() {
        ApplicationModule dem = modules.stream()
                .filter(m -> m.getIdentifier().toString().equals("dem")).findFirst().orElseThrow();
        assertThat(dem.getNamedInterfaces().getByName("api")).isPresent();
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.atomeng.erp");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.atomeng.erp.dem..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.cst..", "com.atomeng.erp.fcd..",
                        "com.atomeng.erp.sal..", "com.atomeng.erp.eqp..", "com.atomeng.erp.rpt..", "com.atomeng.erp.qar..",
                        "com.atomeng.erp.core.application..", "com.atomeng.erp.core.domain..", "com.atomeng.erp.core.adapter..",
                        "com.atomeng.erp.fac.application..", "com.atomeng.erp.fac.domain..", "com.atomeng.erp.fac.adapter..",
                        "com.atomeng.erp.hrq.application..", "com.atomeng.erp.hrq.domain..", "com.atomeng.erp.hrq.adapter..")
                .check(classes);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("com.atomeng.erp.dem..")
                .should().dependOnClassesThat().resideInAnyPackage("com.atomeng.erp.dem..")
                .check(classes);
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
