// 업무 모듈 RPT 보고서 엔진 (설계 06 v0.2 / 06-IMPL, 스키마 rpt, API /api/v1/rpt)
// 다른 업무 모듈에는 core::api · fac::api · eqp::api · aip::api 공개 타입으로만 의존 (Modulith allowedDependencies).
// FAC 와는 rpt.report.published 이벤트 계약(fac::api ReportPublishedContract)으로 연결.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":common:common-evidence"))
    implementation(project(":modules:core"))   // core::api — Modulith 가 api 외 참조를 차단
    implementation(project(":modules:fac"))    // fac::api(ReportPublishedContract — rpt.report.published 계약)
    implementation(project(":modules:eqp"))    // eqp::api(CalibrationValidityPort — BR-RPT-003 사전검증)
    implementation(project(":modules:aip"))    // aip::api(AipJobApi — AI 소견 초안 동기 Job, 설계 11 v0.3 B6)
    implementation(project(":modules:fld"))    // fld::api(FldSurveyQuery — 스냅샷 결함 원천, 06-IMPL Step 3)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")
    implementation("org.springframework.data:spring-data-commons")   // common-web PageResponse

    testImplementation(project(":common:common-testing"))
    testImplementation(libs.wiremock)
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
