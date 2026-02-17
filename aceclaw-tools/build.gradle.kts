// aceclaw-tools: Built-in tools — file, bash, search, web, git

dependencies {
    implementation(project(":aceclaw-core"))
    implementation(project(":aceclaw-security"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
