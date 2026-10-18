// 업무 모듈 FAC 시설물 마스터 (설계 02 v0.3, 스키마 fac, API /api/v1/fac) — core 에는 core::api 로만 의존
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")

    testImplementation(project(":common:common-testing"))
    testImplementation(libs.wiremock)
    testImplementation(project(":modules:core"))   // FK 대상 core 스키마 마이그레이션
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
