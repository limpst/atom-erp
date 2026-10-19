// 업무 모듈 DEM 해체공사 · 구조감리 (설계 10 v0.3 / 10-IMPL, 스키마 dem, API /api/v1/dem)
// 다른 업무 모듈에는 core::api · fac::api · hrq::api 공개 타입으로만 의존 (Modulith allowedDependencies).
// FAC 와는 dem.demolition.completed 이벤트 계약(fac::api DemolitionCompletedContract)으로 연결.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":common:common-evidence"))   // 증거 사진 해시 재계산(core 첨부 원본 객체)
    implementation(project(":modules:core"))   // core::api — Modulith 가 api 외 참조를 차단
    implementation(project(":modules:fac"))    // fac::api(DemolitionCompletedContract — dem.demolition.completed 계약)
    implementation(project(":modules:hrq"))    // hrq::api(HrqEvents · HrqQueryApi — 감리자 자격 · 퇴사)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")

    testImplementation(project(":common:common-testing"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
