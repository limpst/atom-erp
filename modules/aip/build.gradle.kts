// 업무 모듈 AIP AI 플랫폼 (설계 11 v0.3 / 11-IMPL, 스키마 aip, API /api/v1/aip)
// 다른 업무 모듈에는 core::api 공개 타입으로만 의존 (Modulith allowedDependencies). RPT · QAR 은 aip::api(AipJobApi)를 동기 호출한다.
// 추론 자체는 Python 워커(services/aip-worker, FastAPI)가 한다 — 이 모듈은 Job 큐 · 레지스트리 · 감사 · 제안/피드백 · 계약을 맡는다.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":modules:core"))   // core::api — Modulith 가 api 외 참조를 차단
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")

    testImplementation(project(":common:common-testing"))
    testImplementation(libs.wiremock)
    testImplementation(libs.json.schema.validator)
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
