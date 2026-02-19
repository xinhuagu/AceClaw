// aceclaw-mcp: MCP protocol client — stdio transport, config-driven server management

dependencies {
    implementation(project(":aceclaw-core"))

    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}
