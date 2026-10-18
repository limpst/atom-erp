// 파일·증거 저장 (설계 11장, ATOM-LIB-001 C11): 객체 저장소 포트, 로컬 파일시스템 어댑터(WORM 모사), SHA-256, 썸네일
plugins { `java-library` }

dependencies {
    api(project(":common:common-core"))
    api(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")
}
