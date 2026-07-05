// aceclaw-mcp: MCP protocol client — stdio transport, config-driven server management

dependencies {
    implementation(project(":aceclaw-core"))
    // api(): McpToolBridge implements CapabilityAware and exposes
    // Capability in toCapability()'s return type, so the dependency is
    // part of this module's public Java API. (aceclaw-tools has the same
    // shape on every built-in tool but still uses implementation(); flip
    // it in a follow-up consistency PR.)
    api(project(":aceclaw-security"))

    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}
