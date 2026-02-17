package dev.chelava.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unix Domain Socket listener for inter-process communication.
 *
 * <p>Listens on {@code ~/.chelava/chelava.sock} and dispatches each accepted
 * connection to a {@link ConnectionHandler} on a virtual thread.
 */
public final class UdsListener {

    private static final Logger log = LoggerFactory.getLogger(UdsListener.class);

    private static final String SOCKET_FILE_NAME = "chelava.sock";
    private static final Path DEFAULT_DIR = Path.of(System.getProperty("user.home"), ".chelava");

    /**
     * Functional interface for handling an accepted socket connection.
     */
    @FunctionalInterface
    public interface ConnectionHandler {

        /**
         * Handles a single client connection. The channel is closed by the
         * caller after this method returns.
         *
         * @param channel the accepted socket channel
         */
        void handle(SocketChannel channel);
    }

    private final Path socketPath;
    private final ConnectionHandler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocketChannel serverChannel;
    private volatile Thread acceptThread;

    /**
     * Creates a UDS listener at the default socket location with the given handler.
     *
     * @param handler callback invoked for each accepted connection
     */
    public UdsListener(ConnectionHandler handler) {
        this(DEFAULT_DIR.resolve(SOCKET_FILE_NAME), handler);
    }

    /**
     * Creates a UDS listener at the specified socket path (useful for testing).
     *
     * @param socketPath path for the Unix domain socket file
     * @param handler    callback invoked for each accepted connection
     */
    public UdsListener(Path socketPath, ConnectionHandler handler) {
        this.socketPath = socketPath;
        this.handler = handler;
    }

    /**
     * Starts the listener: removes any stale socket file, binds, and begins
     * accepting connections on a dedicated virtual thread.
     *
     * @throws IOException if the socket cannot be created or bound
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            log.warn("UDS listener already running at {}", socketPath);
            return;
        }

        Files.createDirectories(socketPath.getParent());

        // Remove stale socket file from a previous run
        Files.deleteIfExists(socketPath);

        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));

        // Set socket file permissions to owner-only (700)
        try {
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX file permissions not supported on this OS; skipping");
        }

        acceptThread = Thread.ofVirtual().name("chelava-uds-accept").start(this::acceptLoop);
        log.info("UDS listener started at {}", socketPath);
    }

    /**
     * Stops the listener: closes the server channel and deletes the socket file.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        log.info("Stopping UDS listener at {}", socketPath);

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException e) {
            log.warn("Error closing server channel", e);
        }

        // Wait briefly for the accept thread to finish
        if (acceptThread != null) {
            try {
                acceptThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            Files.deleteIfExists(socketPath);
            log.info("Socket file deleted: {}", socketPath);
        } catch (IOException e) {
            log.warn("Failed to delete socket file {}", socketPath, e);
        }
    }

    /**
     * Returns {@code true} if the listener is currently accepting connections.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the path of the socket file managed by this listener.
     */
    public Path socketPath() {
        return socketPath;
    }

    // -- internal --------------------------------------------------------

    private void acceptLoop() {
        while (running.get()) {
            try {
                SocketChannel client = serverChannel.accept();
                Thread.ofVirtual().name("chelava-uds-conn").start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Error accepting UDS connection; continuing", e);
                }
                // If not running, the channel was closed intentionally — exit quietly
            }
        }
        log.debug("Accept loop exited");
    }

    private void handleConnection(SocketChannel client) {
        try (client) {
            handler.handle(client);
        } catch (Exception e) {
            log.error("Unhandled error in connection handler", e);
        }
    }
}
