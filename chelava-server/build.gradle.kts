// chelava-server: WebSocket listener for IDE/remote clients

dependencies {
    implementation(project(":chelava-daemon"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
