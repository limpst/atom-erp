rootProject.name = "atom-erp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// 공통 서비스 라이브러리 (모듈 라이브러리 ATOM-LIB-001 §5: com.atomeng.common.*)
include(
    ":common:common-core",
    ":common:common-web",
    ":common:common-identity",
    ":common:common-events",
    ":common:common-audit",
    ":common:common-evidence",
    ":common:common-testing",
)
// 업무 모듈 (com.atomeng.erp.{mod}) — Phase 0은 core만
include(":modules:core", ":modules:fac", ":modules:hrq", ":modules:eqp", ":modules:cst", ":modules:fcd", ":modules:sal", ":modules:rpt", ":modules:qar", ":modules:dem", ":modules:ptl")
// 실행 앱
include(":apps:erp-server")
