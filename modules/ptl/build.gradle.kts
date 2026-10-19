// 업무 모듈 PTL 고객 포털 (설계 09 v0.3 / 09-IMPL, 스키마 ptl, API /api/v1/ptl — CUSTOMER 전용)
// 다른 업무 모듈에는 core::api · fac::api · rpt::api · sal::api 공개 타입으로만 의존 (Modulith allowedDependencies).
// SAL 과는 ptl.service-request.submitted 이벤트 계약(sal::api SalSubscriptionContracts)으로 연결.
plugins { `java-library` }

dependencies {
    implementation(project(":common:common-core"))
    implementation(project(":common:common-web"))
    implementation(project(":common:common-identity"))   // AtomPrincipal(cid 클레임) · ProjectScopeResolver(프로젝트 → 고객사)
    implementation(project(":common:common-events"))
    implementation(project(":common:common-audit"))
    implementation(project(":modules:core"))   // core::api
    implementation(project(":modules:fac"))    // fac::api(FacQueryApi · FacEvents — 시설물 · 점검 일정 · 등급 이벤트)
    implementation(project(":modules:rpt"))    // rpt::api(RptPublishedDocumentQuery · RptEvents — 납품 보고서 · 정정본)
    implementation(project(":modules:sal"))    // sal::api(SalEvents · SalQueryApi · SalSubscriptionContracts)
    implementation("org.apache.pdfbox:pdfbox:3.0.5")   // 다운로드 워터마크(B7.1) — 보이는 문구 + Info/XMP 메타데이터
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.modulith:spring-modulith-api")

    testImplementation(project(":common:common-testing"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
