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
    implementation(project(":modules:hrq"))
    implementation(project(":modules:cst"))
    implementation(project(":modules:fcd"))
    implementation(project(":modules:eqp"))
    implementation(project(":modules:sal"))
    implementation(project(":modules:rpt"))
    implementation(project(":modules:qar"))
    implementation(project(":modules:dem"))
    implementation(project(":modules:ptl"))
    implementation(project(":modules:aip"))
    implementation(project(":modules:fld"))
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
    testImplementation("org.flywaydb:flyway-core")   // 마이그레이션 기준선 · 업그레이드 경로 시험(migration 패키지)
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(libs.wiremock)
    testImplementation(libs.json.schema.validator)   // AIP 워커 계약 시험(OpenAPI 스키마 검증)
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation(libs.archunit)
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")   // FCD 통합 테스트: 합성 XLSX 명세서 생성
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.5")   // PTL 통합 테스트: 워터마크 PDF 검사(TC-PTL-04)
}

tasks.named<Test>("test") {
    // 마이그레이션 기준선(MigrationBaselineTest): 파일이 바뀌면 시험 다시 실행. -PupdateMigrationBaseline 이면 기준선을 다시 쓴다
    inputs.file(rootProject.file("db/migration-baseline.txt")).withPropertyName("migrationBaseline")
    if (providers.gradleProperty("updateMigrationBaseline").isPresent) {
        systemProperty("atom.migration.baseline.update", "true")
        outputs.upToDateWhen { false }
    }
}
