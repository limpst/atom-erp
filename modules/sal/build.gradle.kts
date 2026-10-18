// 업무 모듈 SAL 수주·영업 (설계 01 v0.3 / 01-IMPL, 스키마 sal, API /api/v1/sal)
// 다른 업무 모듈에는 core::api · fac::api 공개 타입으로만 의존 (Modulith allowedDependencies). CST 와는 sal.contract.signed 이벤트 계약만.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":modules:core"))   // core::api(ProjectCommandApi) — Modulith 가 api 외 참조를 차단
    implementation(project(":modules:fac"))    // fac::api(FacEvents.InspectionDueApproaching)
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
