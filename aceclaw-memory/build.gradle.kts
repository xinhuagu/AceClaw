// aceclaw-memory: Context management, auto-memory, self-learning, HMAC-signed memory files

dependencies {
    implementation(project(":aceclaw-core"))
    implementation(project(":aceclaw-infra"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.commonmark:commonmark")
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("ch.qos.logback:logback-classic")
}
