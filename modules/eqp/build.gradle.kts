// 업무 모듈 EQP 장비 관리 (설계 04 v0.2 / 04-IMPL, 스키마 eqp, API /api/v1/eqp)
// 다른 업무 모듈에는 core::api 공개 타입으로만 의존 (Modulith allowedDependencies). CST 와는 이벤트 계약만.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")
    implementation("org.springframework.data:spring-data-commons")   // common-web PageResponse

    testImplementation(project(":common:common-testing"))
    testImplementation(project(":modules:core"))   // FK 대상 core 스키마 마이그레이션
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
