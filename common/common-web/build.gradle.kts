// HTTP 표준 (설계 9장): RFC 7807 problem+json, 멱등 키 필터, If-Match/ETag, 페이징 DTO
plugins { `java-library` }

dependencies {
    api(project(":common:common-core"))
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework:spring-orm")
}
