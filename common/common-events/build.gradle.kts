// 이벤트·Outbox (설계 10장, ATOM-LIB-001 C3): 트랜잭션 outbox 발행, Relay(백오프·DLQ), 컨슈머 멱등
plugins { `java-library` }

dependencies {
    api(project(":common:common-core"))
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-aop")
    api("org.springframework.boot:spring-boot-starter-json")
    api("io.micrometer:micrometer-core")
}
