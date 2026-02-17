// chelava-daemon: Persistent daemon process — boot, lock, session management, UDS listener

dependencies {
    implementation(project(":chelava-core"))
    implementation(project(":chelava-infra"))
    implementation(project(":chelava-llm"))
    implementation(project(":chelava-tools"))
    implementation(project(":chelava-memory"))
    implementation(project(":chelava-security"))
    implementation(project(":chelava-mcp"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")
}
