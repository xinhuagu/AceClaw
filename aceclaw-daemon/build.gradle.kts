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
    runtimeOnly("ch.qos.logback:logback-classic")
}
