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

    testImplementation(project(":common:common-testing"))
    testImplementation(project(":modules:core"))   // FK 대상 core 스키마 마이그레이션
    testImplementation(project(":modules:fac"))    // project_facility → fac.building
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
