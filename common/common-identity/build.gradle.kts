// 신원·RBAC (설계 8장, ATOM-LIB-001 C1): Keycloak OIDC 리소스 서버, 역할 12종, @RequirePermission
plugins { `java-library` }

dependencies {
    api(project(":common:common-core"))
    api(project(":common:common-web"))
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-aop")
    api("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.security:spring-security-test")
}
