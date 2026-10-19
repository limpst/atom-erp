// 업무 모듈 FLD 현장조사 앱 서버 측 (설계 05 v0.2 / 05-IMPL, 스키마 fld, API /api/v1/fld)
// 다른 업무 모듈에는 core::api · fac::api · eqp::api · aip::api 공개 타입으로만 의존 (Modulith allowedDependencies).
// Flutter 앱(mobile/)은 이 저장소 범위 밖 — 앱이 쓸 동기화 프로토콜(push/pull/충돌/이어 올리기)을 서버에서 구현 · 시험한다.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":common:common-evidence"))   // 청크 임시 저장(ObjectStorage) · 스트리밍 SHA-256
    implementation(project(":modules:core"))   // core::api(AttachmentIngestApi — 원본 WORM · 해시 체인)
    implementation(project(":modules:fac"))    // fac::api(FacQueryApi.locationNode · building)
    implementation(project(":modules:eqp"))    // eqp::api(CalibrationValidityPort · MeasurementTracePort)
    implementation(project(":modules:aip"))    // aip::api(AipJobApi — 사진 균열 정밀 분석 동기 Job)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")

    testImplementation(project(":common:common-testing"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
