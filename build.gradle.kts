plugins {
    java
    id("org.graalvm.buildtools.native") version "0.10.4" apply false
}

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test

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

    if (name == "aceclaw-daemon" || name == "aceclaw-memory" || name == "aceclaw-core") {
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val includes = when (name) {
            "aceclaw-daemon" -> listOf(
                    "dev.aceclaw.daemon.CandidatePipelineIntegrationTest",
                    "dev.aceclaw.daemon.StreamingAgentHandlerCandidateInjectionTest",
                    "dev.aceclaw.daemon.AceClawConfigPersistenceTest"
            )
            "aceclaw-memory" -> listOf(
                    "dev.aceclaw.memory.CandidateStoreTest",
                    "dev.aceclaw.memory.CandidateStateMachineTest",
                    "dev.aceclaw.memory.CandidatePromptAssemblerTest"
            )
            else -> listOf(
                    "dev.aceclaw.core.agent.SkillRegistryTest",
                    "dev.aceclaw.core.agent.SkillContentResolverTest",
                    "dev.aceclaw.core.agent.AgentLoopIntegrationTest"
            )
        }

        tasks.register<Test>("continuousLearningSmokeTest") {
            group = "verification"
            description = "Runs focused smoke tests for pre-merge quality gates."
            testClassesDirs = sourceSets.getByName("test").output.classesDirs
            classpath = sourceSets.getByName("test").runtimeClasspath
            filter {
                includes.forEach { includeTestsMatching(it) }
                isFailOnNoMatchingTests = true
            }
        }
    }
}

tasks.register("continuousLearningSmoke") {
    group = "verification"
    description = "Runs end-to-end continuous-learning smoke tests across core modules."
    dependsOn(
            ":aceclaw-daemon:continuousLearningSmokeTest",
            ":aceclaw-memory:continuousLearningSmokeTest",
            ":aceclaw-core:continuousLearningSmokeTest"
    )
}

tasks.register<Exec>("replayQualityGate") {
    group = "verification"
    description = "Validates replay quality report against hard thresholds."

    val replayReport = providers.gradleProperty("replayReport")
            .orElse("${rootDir}/.aceclaw/metrics/continuous-learning/replay-latest.json")
    val strict = providers.gradleProperty("replayGateStrict")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(true)
    val minSuccessRateDelta = providers.gradleProperty("replayMinSuccessRateDelta").orElse("0.00")
    val maxTokenDelta = providers.gradleProperty("replayMaxTokenDelta").orElse("200.00")
    val maxLatencyDeltaMs = providers.gradleProperty("replayMaxLatencyDeltaMs").orElse("500.00")
    val failOnLatency = providers.gradleProperty("replayFailOnLatency")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(false)
    val maxFailureDistDelta = providers.gradleProperty("replayMaxFailureDistDelta").orElse("0.15")
    val maxTokenEstimationErrorRatio = providers.gradleProperty("replayMaxTokenEstimationErrorRatio").orElse("0.25")
    val enforceAntiPatternFpRate = providers.gradleProperty("replayEnforceAntiPatternFalsePositiveRate")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(false)
    val maxAntiPatternFpRate = providers.gradleProperty("replayMaxAntiPatternFalsePositiveRate").orElse("0.50")

    commandLine(
            "bash", "${rootDir}/scripts/replay-quality-gate.sh",
            "--report", replayReport.get(),
            "--min-success-rate-delta", minSuccessRateDelta.get(),
            "--max-token-delta", maxTokenDelta.get(),
            "--max-latency-delta-ms", maxLatencyDeltaMs.get(),
            "--fail-on-latency", failOnLatency.get().toString(),
            "--max-failure-dist-delta", maxFailureDistDelta.get(),
            "--max-token-estimation-error-ratio", maxTokenEstimationErrorRatio.get(),
            "--enforce-anti-pattern-fp-rate", enforceAntiPatternFpRate.get().toString(),
            "--max-anti-pattern-fp-rate", maxAntiPatternFpRate.get()
    )
    if (strict.get()) args("--strict")
}

tasks.register<Exec>("generateReplayReport") {
    group = "verification"
    description = "Generates replay quality report from learning=off/on case results."

    val replayCasesInput = providers.gradleProperty("replayCasesInput")
            .orElse(providers.gradleProperty("replayInput"))
            .orElse("${rootDir}/docs/reports/samples/replay-cases-sample.json")
    val replayCasesManifestInput = providers.gradleProperty("replayCasesManifestInput")
            .orElse("${rootDir}/.aceclaw/metrics/continuous-learning/replay-cases.manifest.json")
    val replayReport = providers.gradleProperty("replayReport")
            .orElse("${rootDir}/.aceclaw/metrics/continuous-learning/replay-latest.json")

    commandLine(
            "bash",
            "${rootDir}/scripts/generate-replay-report.sh",
            "--input", replayCasesInput.get(),
            "--manifest", replayCasesManifestInput.get(),
            "--output", replayReport.get()
    )
}

tasks.register<Exec>("validateReplaySuite") {
    group = "verification"
    description = "Validates replay prompt suite schema and category coverage."

    val replayPromptsInput = providers.gradleProperty("replayPromptsInput")
            .orElse(providers.gradleProperty("replayInput"))
            .orElse("${rootDir}/docs/reports/samples/replay-prompts-sample.json")
    val replaySuiteMinPerCategory = providers.gradleProperty("replaySuiteMinPerCategory").orElse("3")

    commandLine(
            "bash",
            "${rootDir}/scripts/validate-replay-suite.sh",
            "--input", replayPromptsInput.get(),
            "--min-per-category", replaySuiteMinPerCategory.get()
    )
}

tasks.register("generateReplayCases") {
    group = "verification"
    description = "Runs replay prompts (off/on) and emits replay-cases JSON."
    dependsOn("validateReplaySuite", ":aceclaw-cli:runReplayCases")
}

tasks.register("preMergeCheck") {
    group = "verification"
    description = "Single pre-merge quality gate: build, smoke, and replay quality thresholds."
    dependsOn("build", "continuousLearningSmoke", "replayQualityGate")
}
