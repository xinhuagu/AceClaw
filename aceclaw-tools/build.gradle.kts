// aceclaw-tools: Built-in tools — file, bash, search, web, git

dependencies {
    implementation(project(":aceclaw-core"))
    implementation(project(":aceclaw-memory"))
    implementation(project(":aceclaw-security"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    // HTML parsing for WebFetchTool
    implementation("org.jsoup:jsoup")

    // Browser automation for BrowserTool
    implementation("com.microsoft.playwright:playwright")

    testRuntimeOnly("ch.qos.logback:logback-classic")
}
