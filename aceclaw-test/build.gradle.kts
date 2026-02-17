// aceclaw-test: Shared test utilities and fixtures

dependencies {
    implementation(project(":aceclaw-core"))
    implementation(project(":aceclaw-daemon"))

    implementation("org.junit.jupiter:junit-jupiter")
    implementation("org.assertj:assertj-core")
    implementation("org.mockito:mockito-core")
    implementation("org.mockito:mockito-junit-jupiter")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
