// aceclaw-cli: Thin client — connects to daemon via UDS, JLine3 REPL, Picocli commands

plugins {
    application
    id("org.graalvm.buildtools.native")
}

import org.gradle.api.tasks.JavaExec

dependencies {
    implementation(project(":aceclaw-core"))
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
    applicationDefaultJvmArgs = listOf("--enable-preview", "-Dlogback.configurationFile=logback-cli.xml")
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

tasks.register<JavaExec>("runReplayCases") {
    group = "verification"
    description = "Executes replay prompts in learning off/on modes and writes replay-cases JSON."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.aceclaw.cli.ReplayCasesRunnerMain")
    workingDir = rootProject.projectDir

    val replayPromptsInput = providers.gradleProperty("replayPromptsInput")
            .orElse(providers.gradleProperty("replayInput"))
            .orElse("${rootProject.projectDir}/docs/reports/samples/replay-prompts-sample.json")
    val replayCasesOutput = providers.gradleProperty("replayCasesOutput")
            .orElse("${rootProject.projectDir}/.aceclaw/metrics/continuous-learning/replay-cases.json")
    val replayCasesManifestOutput = providers.gradleProperty("replayCasesManifestOutput")
            .orElse("${rootProject.projectDir}/.aceclaw/metrics/continuous-learning/replay-cases.manifest.json")
    val replayProject = providers.gradleProperty("replayProject")
            .orElse(rootProject.projectDir.toString())
    val replayTimeoutMs = providers.gradleProperty("replayTimeoutMs").orElse("180000")
    val replayAutoApprovePermissions = providers.gradleProperty("replayAutoApprovePermissions").orElse("true")

    args(
            "--input", replayPromptsInput.get(),
            "--output", replayCasesOutput.get(),
            "--manifest-output", replayCasesManifestOutput.get(),
            "--project", replayProject.get(),
            "--timeout-ms", replayTimeoutMs.get(),
            "--auto-approve-permissions", replayAutoApprovePermissions.get()
    )
}
