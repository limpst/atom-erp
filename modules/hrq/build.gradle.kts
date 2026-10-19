// 업무 모듈 HRQ 인력·자격 (설계 03 v0.4, 스키마 hrq, API /api/v1/hrq) — core 에는 core::api 로만 의존
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    // core::api(EmployeeDirectoryApi) 공개 타입만 사용 — 내부 패키지 참조는 ModularityTests 가 차단
    implementation(project(":modules:core"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")

    testImplementation(project(":common:common-testing"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
