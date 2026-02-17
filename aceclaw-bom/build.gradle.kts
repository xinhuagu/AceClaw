plugins {
    `java-platform`
}

group = "dev.aceclaw"
version = "0.1.0-SNAPSHOT"


dependencies {
    constraints {
        // CLI
        api("info.picocli:picocli:4.7.6")
        api("info.picocli:picocli-codegen:4.7.6")

        // Terminal I/O
        api("org.jline:jline:3.27.1")
        api("org.jline:jline-terminal:3.27.1")
        api("org.jline:jline-terminal-jni:3.27.1")
        api("org.jline:jline-reader:3.27.1")

        // JSON
        api("com.fasterxml.jackson.core:jackson-core:2.18.2")
        api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
        api("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
        api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

        // Markdown
        api("org.commonmark:commonmark:0.23.0")

        // Logging
        api("org.slf4j:slf4j-api:2.0.16")
        api("ch.qos.logback:logback-classic:1.5.15")

        // Testing
        api("org.junit.jupiter:junit-jupiter:5.11.4")
        api("org.junit.jupiter:junit-jupiter-api:5.11.4")
        api("org.junit.jupiter:junit-jupiter-params:5.11.4")
        api("org.mockito:mockito-core:5.14.2")
        api("org.mockito:mockito-junit-jupiter:5.14.2")
        api("org.assertj:assertj-core:3.27.3")
        api("com.tngtech.archunit:archunit-junit5:1.3.0")
    }
}
