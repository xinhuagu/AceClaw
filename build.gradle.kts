plugins {
    java
    id("org.graalvm.buildtools.native") version "0.10.4" apply false
}

allprojects {
    group = "dev.aceclaw"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Skip java plugin for BOM (java-platform)
    if (name == "aceclaw-bom") return@subprojects

    apply(plugin = "java-library")

    dependencies {
        // All modules use the BOM for version management
        implementation(platform(project(":aceclaw-bom")))
        testImplementation(platform(project(":aceclaw-bom")))
        annotationProcessor(platform(project(":aceclaw-bom")))

        // Common test dependencies
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("--enable-preview"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }

    tasks.withType<JavaExec> {
        jvmArgs("--enable-preview")
    }
}
