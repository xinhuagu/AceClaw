// aceclaw-cli: Thin client — connects to daemon via UDS, JLine3 REPL, Picocli commands

plugins {
    application
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":aceclaw-daemon"))
    implementation(project(":aceclaw-llm"))

    implementation("info.picocli:picocli")
    annotationProcessor("info.picocli:picocli-codegen")

    implementation("org.jline:jline")
    implementation("org.jline:jline-terminal")
    implementation("org.jline:jline-terminal-jni")
    implementation("org.jline:jline-reader")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.commonmark:commonmark")
    implementation("org.commonmark:commonmark-ext-gfm-tables")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")
}

application {
    mainClass = "dev.aceclaw.cli.AceClawMain"
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "aceclaw"
            mainClass = "dev.aceclaw.cli.AceClawMain"
            buildArgs.addAll(
                "--enable-preview",
                "-O2",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces"
            )
        }
    }
}
