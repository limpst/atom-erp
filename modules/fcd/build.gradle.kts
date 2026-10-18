// 업무 모듈 FCD 법인카드 관리·부정사용 탐지 (설계 12 v0.x / 12-IMPL, 스키마 fcd, API /api/v1/fcd)
// 다른 업무 모듈에는 core::api · hrq::api 공개 타입으로만 의존 (Modulith allowedDependencies)
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    // hrq::api(HrqEvents.EmployeeTerminated) 공개 타입만 사용 — 내부 패키지 참조는 ModularityTests 가 차단
    implementation(project(":modules:hrq"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")
    // 룰셋 YAML(Python 프로토타입과 같은 포맷) — SafeConstructor 로만 읽는다
    implementation("org.yaml:snakeyaml")
    // Excel 수집: POI XSSF SAX(이벤트) 스트리밍 — 행을 메모리에 모두 올리지 않는다(NFR-FCD-003)
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    testImplementation(project(":common:common-testing"))
    testImplementation(project(":modules:core"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
