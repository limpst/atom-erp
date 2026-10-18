// 테스트 지원: Docker 없이 PostgreSQL 16을 내장 기동(zonky embedded-postgres, 바이너리는 Maven Central)
plugins { `java-library` }

dependencies {
    api(libs.embedded.postgres)
    api(platform(libs.embedded.postgres.binaries.bom))
    // Windows 개발 PC + Linux CI 바이너리. 실행 OS에 맞는 것만 사용된다.
    runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64")
    runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64")
    api("org.postgresql:postgresql")
}
