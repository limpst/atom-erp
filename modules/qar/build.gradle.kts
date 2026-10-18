// 업무 모듈 QAR 품질·검토 (설계 07 v0.3 / 07-IMPL, 스키마 qar, API /api/v1/qar)
// 다른 업무 모듈에는 core::api · rpt::api 공개 타입으로만 의존 (Modulith allowedDependencies).
// RPT 와는 rpt::api RptRevisionQuery(조회) + qar.review.approved/rejected 이벤트(RptSubscriptionContracts)로 연결.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":modules:core"))   // core::api — Modulith 가 api 외 참조를 차단
    implementation(project(":modules:rpt"))    // rpt::api(RptRevisionQuery · RptSubscriptionContracts)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")

    testImplementation(project(":common:common-testing"))
    testImplementation(libs.wiremock)
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
