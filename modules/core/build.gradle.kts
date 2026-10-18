// 업무 모듈 core (설계 4장 표: 스키마 core, API /api/v1/core) — Project · Client · Employee · Attachment
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
    runtimeOnly("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation(project(":common:common-testing"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
