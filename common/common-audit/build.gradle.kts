// 감사추적·해시체인 (NFR-CORE-07, ATOM-LIB-001 C2)
plugins { `java-library` }

dependencies {
    api(project(":common:common-core"))
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-json")
    api("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework:spring-web")
}
