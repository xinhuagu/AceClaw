// aceclaw-tools: Built-in tools — file, bash, search, web, git

dependencies {
    implementation(project(":aceclaw-core"))
    implementation(project(":aceclaw-memory"))
    // api(): every built-in tool here implements CapabilityAware and exposes
    // dev.aceclaw.security.Capability as the return type of toCapability(),
    // so the dependency is part of this module's public Java API. Matches
    // the scope already used in :aceclaw-mcp. (Tracked as #497.)
    api(project(":aceclaw-security"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    // HTML parsing for WebFetchTool
    implementation("org.jsoup:jsoup")

    // Browser automation for BrowserTool
    implementation("com.microsoft.playwright:playwright")

    testRuntimeOnly("ch.qos.logback:logback-classic")
}
