// aceclaw-dashboard: TypeScript/React dashboard built via npm. Wired into Gradle so
// `./gradlew build` produces aceclaw-daemon/build/resources/main/META-INF/dashboard
// containing index.html + assets, ready for the Javalin static-files handler (#446).
//
// Skip this module (and the bundled-dashboard packaging in :aceclaw-daemon) by passing
// -Pno-dashboard. That's for backend contributors who don't have Node 20 installed; the
// daemon JAR will still build, but `aceclaw dashboard` will return a 404 explaining the
// dashboard wasn't bundled.

import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem

// Use isPresent so `-Pno-dashboard` (no explicit value) works. Gradle stores a
// missing-value property as empty string, which `.toBoolean()` reads as false.
val skipDashboard = providers.gradleProperty("no-dashboard").isPresent

val dashboardSrc = layout.projectDirectory.dir("src")
val dashboardDist = layout.projectDirectory.dir("dist")
val nodeModules = layout.projectDirectory.dir("node_modules")

// Windows ships npm as npm.cmd / npm.bat — Java's ProcessBuilder doesn't honor
// PATHEXT, so `commandLine("npm", ...)` would fail with "No such file" on
// platform-full (Windows). Resolve the shim suffix once.
val npmCmd = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"

// `npm ci` — deterministic install from package-lock.json. Up-to-date when the lockfile
// hasn't changed and node_modules already exists. Faster than `npm install` and won't
// silently mutate the lockfile.
//
// NOT marked cacheable: node_modules is platform-specific (esbuild, sharp, etc. ship
// native binaries) and >100 MB, so build-cache restoration is slower than just running
// `npm ci` again. Up-to-date check via inputs/outputs gates re-runs locally.
val npmCi = tasks.register<Exec>("npmCi") {
    group = "build"
    description = "Installs dashboard npm dependencies via npm ci"

    inputs.file("package-lock.json")
    inputs.file("package.json")
    outputs.dir(nodeModules)

    workingDir = projectDir
    commandLine(npmCmd, "ci")

    onlyIf { !skipDashboard }
}

// `npm run build` — produces dist/ via tsc + vite build. Inputs cover everything the
// build reads; outputs cover everything Vite produces. Up-to-date check skips re-running
// when source hasn't changed.
val npmBuild = tasks.register<Exec>("npmBuild") {
    group = "build"
    description = "Builds the dashboard production bundle via npm run build"
    dependsOn(npmCi)

    inputs.dir(dashboardSrc)
    inputs.file("index.html")
    inputs.file("vite.config.ts")
    inputs.file("tsconfig.json")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir(dashboardDist)
    outputs.cacheIf { true }

    workingDir = projectDir
    commandLine(npmCmd, "run", "build")

    onlyIf { !skipDashboard }
}

// `./gradlew :aceclaw-dashboard:build` — wire npmBuild into the standard build lifecycle
// so the daemon can dependsOn(":aceclaw-dashboard:build") with no extra configuration.
tasks.register("build") {
    group = "build"
    description = "Builds the dashboard production bundle (alias for npmBuild)"
    dependsOn(npmBuild)
}

tasks.register<Delete>("clean") {
    group = "build"
    description = "Removes the dashboard dist/ output"
    delete(dashboardDist)
}
