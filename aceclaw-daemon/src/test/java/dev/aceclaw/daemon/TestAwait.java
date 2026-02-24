package dev.aceclaw.daemon;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

final class TestAwait {

    private TestAwait() {}

    static void waitUntil(String description, long timeoutMs, BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(10_000_000L);
        }
        if (!condition.getAsBoolean()) {
            throw new IllegalStateException("Timed out waiting for " + description + " within " + timeoutMs + "ms");
        }
    }

    static void waitForSocketReady(Path socketPath, long timeoutMs) {
        waitUntil("daemon socket ready", timeoutMs, () -> {
            if (!Files.exists(socketPath)) {
                return false;
            }
            try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                probe.connect(UnixDomainSocketAddress.of(socketPath));
                return true;
            } catch (IOException ignored) {
                return false;
            }
        });
    }
}
