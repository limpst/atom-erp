plugins { `java-library` }

dependencies {
    api(platform(libs.spring.boot.bom))
    api("jakarta.persistence:jakarta.persistence-api")
    api("org.springframework.data:spring-data-jpa")
    api("com.fasterxml.jackson.core:jackson-databind")
}
