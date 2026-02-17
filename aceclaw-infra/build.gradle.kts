// aceclaw-infra: Infrastructure backbone — event bus, health monitor, scheduler, shutdown, circuit breaker
// Minimal dependencies, foundational layer

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
}
