// Spring Boot 실행 앱 — 모든 모듈 조립 (설계 6장 apps/erp-server)
// Spring Boot Gradle 플러그인 없이 BOM 만 사용(Gradle 9.x 호환성 이슈 회피). 실행: ./gradlew :apps:erp-server:run
plugins {
    java
    application
}

application {
    mainClass.set("com.atomeng.erp.ErpServerApplication")
}

dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:fac"))
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":common:common-evidence"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-api")
    runtimeOnly("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":common:common-testing"))
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation(libs.archunit)
    testImplementation("org.springframework.security:spring-security-test")
}
