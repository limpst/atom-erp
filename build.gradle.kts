// 루트 빌드: 모든 Java 서브프로젝트 공통 규약 (00-IMPL Step 1의 convention plugin을 루트 subprojects 블록으로 단순화)
plugins {
    base
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

subprojects {
    group = "com.atomeng"
    version = "0.1.0-SNAPSHOT"

    pluginManager.withPlugin("java") {
        tasks.withType<JavaCompile>().configureEach {
            // JDK 21 툴체인이 없는 환경(JBR 25)에서도 Java 21 바이트코드를 만든다(--release 21).
            // JDK 21이 설치된 CI에서는 java.toolchain.languageVersion = 21 로 바꿔도 된다.
            options.release.set(21)
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial,-this-escape", "-Werror"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            systemProperty("file.encoding", "UTF-8")
            systemProperty("user.timezone", "UTC")
            systemProperty("java.awt.headless", "true")
            systemProperty("atom.repo.root", rootDir.absolutePath)
            jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
        dependencies {
            "implementation"(platform(libs.spring.boot.bom))
            "implementation"(platform(libs.modulith.bom))
            "testImplementation"(platform(libs.spring.boot.bom))
            "testImplementation"("org.springframework.boot:spring-boot-starter-test")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
    }
}
