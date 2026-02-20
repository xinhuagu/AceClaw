package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorClassTest {

    @Test
    void classifiesEncodingErrors() {
        assertThat(ErrorClass.classify("MalformedInputException: invalid byte sequence"))
                .isEqualTo(ErrorClass.ENCODING);
        assertThat(ErrorClass.classify("unmappable character for encoding UTF-8"))
                .isEqualTo(ErrorClass.ENCODING);
    }

    @Test
    void classifiesPathNotFoundErrors() {
        assertThat(ErrorClass.classify("File not found: /missing.txt"))
                .isEqualTo(ErrorClass.PATH_NOT_FOUND);
        assertThat(ErrorClass.classify("No such file or directory"))
                .isEqualTo(ErrorClass.PATH_NOT_FOUND);
        assertThat(ErrorClass.classify("Directory does not exist: /tmp/gone"))
                .isEqualTo(ErrorClass.PATH_NOT_FOUND);
    }

    @Test
    void classifiesPermissionErrors() {
        assertThat(ErrorClass.classify("Permission denied: /etc/shadow"))
                .isEqualTo(ErrorClass.PERMISSION);
        assertThat(ErrorClass.classify("Access denied for user"))
                .isEqualTo(ErrorClass.PERMISSION);
        assertThat(ErrorClass.classify("File is not readable"))
                .isEqualTo(ErrorClass.PERMISSION);
    }

    @Test
    void classifiesTimeoutErrors() {
        assertThat(ErrorClass.classify("Command timed out after 30s"))
                .isEqualTo(ErrorClass.TIMEOUT);
        assertThat(ErrorClass.classify("Connection timeout"))
                .isEqualTo(ErrorClass.TIMEOUT);
        assertThat(ErrorClass.classify("command was killed due to timeout"))
                .isEqualTo(ErrorClass.TIMEOUT);
    }

    @Test
    void classifiesSizeLimitErrors() {
        assertThat(ErrorClass.classify("File too large: exceeds 1MB limit"))
                .isEqualTo(ErrorClass.SIZE_LIMIT);
        assertThat(ErrorClass.classify("Output truncated at 30000 characters"))
                .isEqualTo(ErrorClass.SIZE_LIMIT);
        assertThat(ErrorClass.classify("file size exceeds maximum limit"))
                .isEqualTo(ErrorClass.SIZE_LIMIT);
    }

    @Test
    void classifiesSyntaxErrors() {
        assertThat(ErrorClass.classify("Invalid regex: unexpected token at position 5"))
                .isEqualTo(ErrorClass.SYNTAX);
        assertThat(ErrorClass.classify("Syntax error near line 42"))
                .isEqualTo(ErrorClass.SYNTAX);
        assertThat(ErrorClass.classify("PatternSyntaxException: pattern syntax"))
                .isEqualTo(ErrorClass.SYNTAX);
    }

    @Test
    void unknownForUnrecognizedErrors() {
        assertThat(ErrorClass.classify("Some random error"))
                .isEqualTo(ErrorClass.UNKNOWN);
        assertThat(ErrorClass.classify("This is a completely new kind of problem"))
                .isEqualTo(ErrorClass.UNKNOWN);
    }

    @Test
    void nullAndBlankReturnUnknown() {
        assertThat(ErrorClass.classify(null)).isEqualTo(ErrorClass.UNKNOWN);
        assertThat(ErrorClass.classify("")).isEqualTo(ErrorClass.UNKNOWN);
        assertThat(ErrorClass.classify("   ")).isEqualTo(ErrorClass.UNKNOWN);
    }

    @Test
    void caseInsensitiveMatching() {
        assertThat(ErrorClass.classify("PERMISSION DENIED")).isEqualTo(ErrorClass.PERMISSION);
        assertThat(ErrorClass.classify("file NOT FOUND")).isEqualTo(ErrorClass.PATH_NOT_FOUND);
        assertThat(ErrorClass.classify("TIMEOUT")).isEqualTo(ErrorClass.TIMEOUT);
    }

    @Test
    void defaultRecoveryNotEmpty() {
        for (var ec : ErrorClass.values()) {
            assertThat(ec.defaultRecovery())
                    .as("defaultRecovery for %s", ec.name())
                    .isNotBlank();
        }
    }
}
