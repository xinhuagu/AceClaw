// aceclaw-daemon: Persistent daemon process — boot, lock, session management, UDS listener

dependencies {
    implementation(project(":aceclaw-core"))
    implementation(project(":aceclaw-infra"))
    implementation(project(":aceclaw-llm"))
    implementation(project(":aceclaw-tools"))
    implementation(project(":aceclaw-memory"))
    implementation(project(":aceclaw-security"))
    implementation(project(":aceclaw-mcp"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
    implementation("io.javalin:javalin")
    runtimeOnly("ch.qos.logback:logback-classic")
}

// Bundle the built dashboard (issue #446) into the daemon JAR at
// META-INF/dashboard/ so Javalin's static-files handler can serve it from the
// classpath. Skipped when -Pno-dashboard is set (backend devs without Node 20):
// in that case the daemon JAR contains no dashboard, and a GET / returns the
// "dashboard not bundled" 404 from HttpStaticHandler.
//
// Uses {@code isPresent} rather than reading the value so {@code -Pno-dashboard}
// without an explicit {@code =true} works (Gradle stores absent values as empty
// strings, which {@code String.toBoolean()} reads as false — the wrong default).
val skipDashboard = providers.gradleProperty("no-dashboard").isPresent

if (!skipDashboard) {
    val dashboardBuild = tasks.getByPath(":aceclaw-dashboard:build")
    val dashboardDist = project(":aceclaw-dashboard").layout.projectDirectory.dir("dist")

    tasks.named<ProcessResources>("processResources") {
        dependsOn(dashboardBuild)
        from(dashboardDist) {
            into("META-INF/dashboard")
        }
    }
}
