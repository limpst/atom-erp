// 업무 모듈 CST 원가·수익성 (설계 08 v0.4, 스키마 cst, API /api/v1/cst)
// 다른 업무 모듈에는 core::api · hrq::api · fac::api 공개 타입으로만 의존 (Modulith allowedDependencies)
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    // hrq::api(HrqEvents·HrqQueryApi) 공개 타입만 사용 — 내부 패키지 참조는 ModularityTests 가 차단
    implementation(project(":modules:hrq"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")
    // 통장 명세(CSV · XLSX) 업로드 — FCD 와 같은 POI 버전. FCD 파서(RowSource)는 fcd.rules.PyFormat 에 묶여 있어 공통화는 뒤로(설계 §12)
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    testImplementation(project(":common:common-testing"))
    testImplementation(project(":modules:core"))   // FK 대상 core 스키마 마이그레이션
    testImplementation(project(":modules:fac"))    // project_facility → fac.building
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
