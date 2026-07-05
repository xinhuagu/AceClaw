// aceclaw-learning: Self-improvement subsystem -- pattern/error/failure detection,
// learning-decision explanation, signal review. Carved out of aceclaw-daemon
// (which had grown to 29K LoC and was carrying the entire learning pipeline
// alongside its actual responsibilities). Daemon now depends on this module
// and stays focused on UDS/JSON-RPC, agent loop wiring, and lifecycle.
//
// Only depends on aceclaw-core (Turn, ContentBlock, Message) and aceclaw-memory
// (AutoMemoryStore, Insight, candidate types). Crucially: NO daemon dependency,
// so this module can be exercised in unit tests without booting the daemon.

dependencies {
    implementation(project(":aceclaw-core"))
    implementation(project(":aceclaw-memory"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("ch.qos.logback:logback-classic")
}
