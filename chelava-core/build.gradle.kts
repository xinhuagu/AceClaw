// chelava-core: Agent loop, task planner, tool system, LLM client abstractions, agent teams

dependencies {
    api(project(":chelava-sdk"))
    implementation(project(":chelava-infra"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
}
