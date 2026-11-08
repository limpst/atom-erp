package com.atomeng.erp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/**
 * 헥사고날·라이브러리 규칙 (00-IMPL Step 1 ArchitectureRulesTest, ATOM-LIB-001 §9 R1).
 * 프로덕션 클래스만 분석한다(테스트 픽스처 제외).
 */
@AnalyzeClasses(packages = {"com.atomeng.erp", "com.atomeng.common"},
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule domainIsPure = noClasses()
            .that().resideInAPackage("com.atomeng.erp..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.servlet..", "..adapter..", "..application..")
            .because("도메인 계층은 프레임워크 독립 (설계 17.1). jakarta.persistence 는 실용 타협으로 허용(ADR-003)");

    @ArchTest
    static final ArchRule controllersOnlyInAdapterIn = classes()
            .that().areAnnotatedWith(RestController.class)
            .and().resideInAPackage("com.atomeng.erp..")
            .should().resideInAPackage("..adapter.in.web..");

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .because("생성자 주입만 허용");

    @ArchTest
    static final ArchRule commonDoesNotDependOnErpModules = noClasses()
            .that().resideInAPackage("com.atomeng.common..")
            .should().dependOnClassesThat().resideInAPackage("com.atomeng.erp..")
            .because("R1: 공통 → 특화 import 금지 (ATOM-LIB-001 §9)");

    @ArchTest
    static final ArchRule cstLedgerSingleWriter = noClasses()
            .that().resideInAPackage("com.atomeng.erp.cst..")
            .and().doNotHaveFullyQualifiedName("com.atomeng.erp.cst.application.CostLedgerService")
            .should().callMethod(com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.class, "insertLedger",
                    com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.LedgerRow.class)
            .because("08-IMPL Step 6 DoD: 원가 원장 쓰기 진입점은 CostLedgerService 하나");
    @ArchTest
    static final ArchRule cstBillingSingleWriter = noClasses()
            .that().resideInAPackage("com.atomeng.erp.cst..")
            .and().doNotHaveFullyQualifiedName("com.atomeng.erp.cst.application.BillingService")
            .should().callMethod(com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.class, "insertAllocation",
                    com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.AllocationRow.class)
            .orShould().callMethod(com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.class, "insertInvoice",
                    com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.InvoiceRow.class, java.util.UUID.class)
            .because("CST 청구 · 수금(docs/CST_BILLING_COLLECTION_DESIGN.md): 청구 · 배분 쓰기 진입점은 BillingService 하나");
}
