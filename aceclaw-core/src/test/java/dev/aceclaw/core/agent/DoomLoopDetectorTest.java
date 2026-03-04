package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DoomLoopDetectorTest {

    @Test
    void identicalCallBlockedAfter2Failures() {
        var detector = new DoomLoopDetector();
        String tool = "bash";
        String input = "{\"command\":\"npm test\"}";

        // First failure -> warn
        detector.recordResult(tool, input, true, "exit code 1");
        var v1 = detector.preCheck(tool, input);
        assertThat(v1).isInstanceOf(DoomLoopDetector.Verdict.Warn.class);

        // Second failure -> now at BLOCK_THRESHOLD
        detector.recordResult(tool, input, true, "exit code 1");
        var v2 = detector.preCheck(tool, input);
        assertThat(v2).isInstanceOf(DoomLoopDetector.Verdict.Block.class);
        var block = (DoomLoopDetector.Verdict.Block) v2;
        assertThat(block.reason()).contains("Doom loop detected");
        assertThat(block.failCount()).isEqualTo(2);
    }

    @Test
    void differentArgsNotBlocked() {
        var detector = new DoomLoopDetector();
        String tool = "bash";

        detector.recordResult(tool, "{\"command\":\"npm test\"}", true, "fail");
        detector.recordResult(tool, "{\"command\":\"npm test\"}", true, "fail");

        // Same tool but different args -> should allow
        var v = detector.preCheck(tool, "{\"command\":\"npm run build\"}");
        assertThat(v).isInstanceOf(DoomLoopDetector.Verdict.Allow.class);
    }

    @Test
    void successResetsCount() {
        var detector = new DoomLoopDetector();
        String tool = "bash";
        String input = "{\"command\":\"npm test\"}";

        // One failure
        detector.recordResult(tool, input, true, "fail");
        assertThat(detector.preCheck(tool, input)).isInstanceOf(DoomLoopDetector.Verdict.Warn.class);

        // Then success resets
        detector.recordResult(tool, input, false, null);

        // Then one failure again -> should only be at count=1, so warn
        detector.recordResult(tool, input, true, "fail");
        var v = detector.preCheck(tool, input);
        assertThat(v).isInstanceOf(DoomLoopDetector.Verdict.Warn.class);
        assertThat(((DoomLoopDetector.Verdict.Warn) v).failCount()).isEqualTo(1);
    }

    @Test
    void fingerprintNormalization() {
        // JSON key order and whitespace should not affect hash
        String fp1 = DoomLoopDetector.fingerprint("bash", "{\"command\":\"test\",\"timeout\":30}");
        String fp2 = DoomLoopDetector.fingerprint("bash", "{\"timeout\":30,\"command\":\"test\"}");
        String fp3 = DoomLoopDetector.fingerprint("bash", "{ \"timeout\" : 30 , \"command\" : \"test\" }");

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).isEqualTo(fp3);
    }

    @Test
    void cooldownExponentialBackoff() {
        var detector = new DoomLoopDetector();
        String tool = "bash";
        String input = "{\"command\":\"npm test\"}";

        // Fail twice to reach BLOCK_THRESHOLD
        detector.recordResult(tool, input, true, "fail");
        detector.recordResult(tool, input, true, "fail");

        // preCheck transitions into cooldown and returns Block
        var v1 = detector.preCheck(tool, input);
        assertThat(v1).isInstanceOf(DoomLoopDetector.Verdict.Block.class);
        assertThat(((DoomLoopDetector.Verdict.Block) v1).reason()).contains("Doom loop detected");

        // Second preCheck should now be in cooldown (initial cooldown = 2 iterations)
        var v2 = detector.preCheck(tool, input);
        assertThat(v2).isInstanceOf(DoomLoopDetector.Verdict.Block.class);
        assertThat(((DoomLoopDetector.Verdict.Block) v2).reason()).contains("cooldown");

        // Tick cooldowns via other tool calls until cooldown expires
        detector.recordResult("other_tool", "{}", false, null); // tick 1
        detector.recordResult("other_tool", "{}", false, null); // tick 2

        // After enough ticks, cooldown should have expired -> Allow again
        var v3 = detector.preCheck(tool, input);
        assertThat(v3).isInstanceOf(DoomLoopDetector.Verdict.Allow.class);
    }

    @Test
    void nullAndEmptyInputsHandled() {
        var detector = new DoomLoopDetector();

        // null input should not throw
        detector.recordResult("bash", null, true, "error");
        var v1 = detector.preCheck("bash", null);
        assertThat(v1).isInstanceOf(DoomLoopDetector.Verdict.Warn.class);

        // empty input treated same as null
        detector.recordResult("bash", "", true, "error");
        var v2 = detector.preCheck("bash", "");
        assertThat(v2).isInstanceOf(DoomLoopDetector.Verdict.Block.class);

        // null toolName returns Allow
        var v3 = detector.preCheck(null, "{}");
        assertThat(v3).isInstanceOf(DoomLoopDetector.Verdict.Allow.class);
    }

    @Test
    void verdictSealedExhaustive() {
        var allow = new DoomLoopDetector.Verdict.Allow();
        var warn = new DoomLoopDetector.Verdict.Warn("test advice", 1);
        var block = new DoomLoopDetector.Verdict.Block("test reason", 2);

        // Verify switch expression works (sealed exhaustiveness)
        for (DoomLoopDetector.Verdict v : new DoomLoopDetector.Verdict[]{allow, warn, block}) {
            String result = switch (v) {
                case DoomLoopDetector.Verdict.Allow _ -> "allow";
                case DoomLoopDetector.Verdict.Warn w -> "warn:" + w.failCount();
                case DoomLoopDetector.Verdict.Block b -> "block:" + b.failCount();
            };
            assertThat(result).isNotNull();
        }
    }

    @Test
    void fingerprintDifferentToolsSameArgs() {
        String fp1 = DoomLoopDetector.fingerprint("bash", "{\"path\":\"/tmp/test\"}");
        String fp2 = DoomLoopDetector.fingerprint("read_file", "{\"path\":\"/tmp/test\"}");

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void firstCallIsAllowed() {
        var detector = new DoomLoopDetector();
        var v = detector.preCheck("bash", "{\"command\":\"npm test\"}");
        assertThat(v).isInstanceOf(DoomLoopDetector.Verdict.Allow.class);
    }

    @Test
    void fingerprintPathNormalization() {
        // Backslash vs forward slash
        String fp1 = DoomLoopDetector.fingerprint("read_file", "{\"path\":\"/tmp/test/file.txt\"}");
        String fp2 = DoomLoopDetector.fingerprint("read_file", "{\"path\":\"/tmp\\\\test\\\\file.txt\"}");
        assertThat(fp1).isEqualTo(fp2);

        // Removing /./  segments
        String fp3 = DoomLoopDetector.fingerprint("read_file", "{\"path\":\"/tmp/./test/file.txt\"}");
        assertThat(fp1).isEqualTo(fp3);

        // Collapsing multiple slashes
        String fp4 = DoomLoopDetector.fingerprint("read_file", "{\"path\":\"/tmp//test///file.txt\"}");
        assertThat(fp1).isEqualTo(fp4);
    }

    @Test
    void blockTransitionsToCooldownThenExpires() {
        var detector = new DoomLoopDetector();
        String tool = "bash";
        String input = "{\"command\":\"npm test\"}";

        // Fail twice
        detector.recordResult(tool, input, true, "fail");
        detector.recordResult(tool, input, true, "fail");

        // Block transitions fingerprint into cooldown
        assertThat(detector.preCheck(tool, input)).isInstanceOf(DoomLoopDetector.Verdict.Block.class);

        // Still in cooldown
        assertThat(detector.preCheck(tool, input)).isInstanceOf(DoomLoopDetector.Verdict.Block.class);

        // Tick via other tool calls (initial cooldown = 2)
        detector.recordResult("other", "{}", false, null);
        detector.recordResult("other", "{}", false, null);

        // Cooldown expired -> Allow
        assertThat(detector.preCheck(tool, input)).isInstanceOf(DoomLoopDetector.Verdict.Allow.class);

        // Fail again twice -> re-block with doubled cooldown (4)
        detector.recordResult(tool, input, true, "fail2");
        detector.recordResult(tool, input, true, "fail2");
        assertThat(detector.preCheck(tool, input)).isInstanceOf(DoomLoopDetector.Verdict.Block.class);

        // Need 4 ticks to expire this time
        for (int i = 0; i < 4; i++) {
            assertThat(detector.preCheck(tool, input)).isInstanceOf(DoomLoopDetector.Verdict.Block.class);
            detector.recordResult("other", "{}", false, null);
        }
        assertThat(detector.preCheck(tool, input)).isInstanceOf(DoomLoopDetector.Verdict.Allow.class);
    }
}
