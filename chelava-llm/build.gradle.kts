// chelava-llm: LLM provider implementations — Anthropic Claude, OpenAI, Ollama

dependencies {
    implementation(project(":chelava-core"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
}
