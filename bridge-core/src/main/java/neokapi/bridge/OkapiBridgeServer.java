package neokapi.bridge;

import neokapi.bridge.grpc.BridgeServiceImpl;
import neokapi.bridge.io.LocalContentResolver;
import neokapi.bridge.io.LocalOutputWriter;
import neokapi.bridge.model.FilterInfo;
import neokapi.bridge.util.FilterRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.unix.DomainSocketAddress;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the Okapi Bridge gRPC server.
 * Starts a gRPC server on a random port and prints the address to stdout
 * so the Go client can connect. All logging goes to stderr.
 *
 * <p>Flags:
 * <ul>
 *   <li>{@code --list-filters} — Print filter metadata as JSON to stdout and exit.</li>
 *   <li>{@code --idle-timeout <seconds>} — Shut down after N seconds with no active streams.
 *       Default: 0 (no timeout, subprocess mode).</li>
 *   <li>{@code --concurrency <N>} — Number of filter threads. Default: available processors.</li>
 *   <li>{@code --stuck-timeout <seconds>} — Abort if translation queue poll exceeds this.
 *       Default: 120s.</li>
 * </ul>
 */
public class OkapiBridgeServer {

    public static void main(String[] args) {
        long idleTimeoutSeconds = 0;
        long stuckTimeoutSeconds = 120;
        int concurrency = Runtime.getRuntime().availableProcessors();

        for (int i = 0; i < args.length; i++) {
            if ("--list-filters".equals(args[i])) {
                listFiltersAndExit();
                return;
            }
            if ("--idle-timeout".equals(args[i]) && i + 1 < args.length) {
                idleTimeoutSeconds = parseTimeout(args[++i]);
            }
            if ("--concurrency".equals(args[i]) && i + 1 < args.length) {
                concurrency = Integer.parseInt(args[++i]);
            }
            if ("--stuck-timeout".equals(args[i]) && i + 1 < args.length) {
                stuckTimeoutSeconds = parseTimeout(args[++i]);
            }
        }

        System.err.println("[bridge] Okapi Bridge Server starting (gRPC)...");
        System.err.println("[bridge] Concurrency: " + concurrency + " filter threads");

        try {
            BridgeServiceImpl service = new BridgeServiceImpl(
                    new LocalContentResolver(), new LocalOutputWriter(),
                    concurrency, idleTimeoutSeconds, stuckTimeoutSeconds);

            // Check for Unix domain socket path from Go parent process.
            String socketPath = System.getenv("NEOKAPI_BRIDGE_SOCKET");
            Server server;
            String address;

            if (socketPath != null && !socketPath.isEmpty()) {
                server = createUnixSocketServer(service, socketPath);
                address = socketPath;
            } else {
                server = createTcpServer(service);
                address = "localhost:" + server.getPort();
            }

            System.err.println("[bridge] gRPC server started on " + address);
            if (idleTimeoutSeconds > 0) {
                System.err.println("[bridge] Idle timeout: " + idleTimeoutSeconds + "s");
            }
            if (stuckTimeoutSeconds != 120) {
                System.err.println("[bridge] Stuck timeout: " + stuckTimeoutSeconds + "s");
            }

            // Print address to stdout — the Go client reads this first line.
            System.out.println(address);
            System.out.flush();

            // Start parent process heartbeat (deadman's switch).
            // Only in subprocess mode — in daemon mode, the JVM is meant to
            // survive parent exits and rely on idle timeout instead.
            if (idleTimeoutSeconds == 0) {
                startParentHeartbeat();
            }

            // Wait for shutdown signal from the client or idle timeout.
            service.awaitShutdown();

            System.err.println("[bridge] Shutting down gRPC server...");
            server.shutdown();
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }

            System.err.println("[bridge] Server stopped");
        } catch (Exception e) {
            System.err.println("[bridge] Fatal error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Start a watchdog thread that checks if the parent process is still alive.
     * If the parent dies (e.g., kapi crashes or is killed), the JVM exits within 5 seconds.
     * This is the cross-platform equivalent of Linux's PDEATHSIG.
     */
    private static void startParentHeartbeat() {
        ProcessHandle.current().parent().ifPresent(parent -> {
            long parentPid = parent.pid();
            ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "parent-heartbeat");
                t.setDaemon(true);
                return t;
            });
            watchdog.scheduleAtFixedRate(() -> {
                if (!ProcessHandle.of(parentPid).isPresent()) {
                    System.err.println("[bridge] Parent process " + parentPid + " gone, shutting down");
                    System.exit(0);
                }
            }, 5, 5, TimeUnit.SECONDS);
        });
    }

    /**
     * Parse a timeout value. Accepts plain seconds ("30") or duration suffix ("30s").
     */
    private static long parseTimeout(String value) {
        value = value.trim();
        if (value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            System.err.println("[bridge] Invalid timeout value: " + value);
            return 0;
        }
    }

    /**
     * Create a gRPC server listening on a Unix domain socket. Uses Netty's
     * native epoll (Linux) or kqueue (macOS) transport for zero-copy IPC.
     * Falls back to TCP if native transport is unavailable.
     */
    private static Server createUnixSocketServer(BridgeServiceImpl service, String socketPath) throws Exception {
        SocketAddress address = new DomainSocketAddress(socketPath);
        String os = System.getProperty("os.name", "").toLowerCase();

        EventLoopGroup bossGroup;
        EventLoopGroup workerGroup;
        Class<? extends ServerChannel> channelType;

        if (os.contains("linux")) {
            bossGroup = createEventLoopGroup(
                    "io.netty.channel.epoll.EpollEventLoopGroup", 1);
            workerGroup = createEventLoopGroup(
                    "io.netty.channel.epoll.EpollEventLoopGroup", 0);
            channelType = loadChannelClass(
                    "io.netty.channel.epoll.EpollServerDomainSocketChannel");
        } else if (os.contains("mac")) {
            bossGroup = createEventLoopGroup(
                    "io.netty.channel.kqueue.KQueueEventLoopGroup", 1);
            workerGroup = createEventLoopGroup(
                    "io.netty.channel.kqueue.KQueueEventLoopGroup", 0);
            channelType = loadChannelClass(
                    "io.netty.channel.kqueue.KQueueServerDomainSocketChannel");
        } else {
            throw new UnsupportedOperationException("Unix sockets not supported on " + os);
        }

        System.err.println("[bridge] Using Unix domain socket: " + socketPath);
        return NettyServerBuilder.forAddress(address)
                .channelType(channelType)
                .bossEventLoopGroup(bossGroup)
                .workerEventLoopGroup(workerGroup)
                .addService(service)
                .maxInboundMessageSize(64 * 1024 * 1024)
                .flowControlWindow(4 * 1024 * 1024)
                .initialFlowControlWindow(4 * 1024 * 1024)
                .build()
                .start();
    }

    /**
     * Create a gRPC server on a random TCP port (default / fallback).
     */
    private static Server createTcpServer(BridgeServiceImpl service) throws Exception {
        return NettyServerBuilder.forPort(0)
                .addService(service)
                .maxInboundMessageSize(64 * 1024 * 1024)
                .flowControlWindow(4 * 1024 * 1024)
                .initialFlowControlWindow(4 * 1024 * 1024)
                .build()
                .start();
    }

    /**
     * Reflectively create a Netty EventLoopGroup. Uses reflection so the class
     * compiles on all platforms — the native library only loads at runtime.
     */
    @SuppressWarnings("unchecked")
    private static EventLoopGroup createEventLoopGroup(String className, int nThreads) throws Exception {
        Class<?> clazz = Class.forName(className);
        return (EventLoopGroup) clazz.getConstructor(int.class).newInstance(nThreads);
    }

    /**
     * Reflectively load a Netty ServerChannel class.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends ServerChannel> loadChannelClass(String className) throws Exception {
        return (Class<? extends ServerChannel>) Class.forName(className);
    }

    /**
     * Print all discovered filters as a JSON object to stdout, then exit.
     */
    private static void listFiltersAndExit() {
        try {
            List<FilterInfo> filters = FilterRegistry.listFilters();
            Gson gson = new GsonBuilder().create();
            System.out.println("{\"filters\":" + gson.toJson(filters) + "}");
        } catch (Exception e) {
            System.err.println("[bridge] Error listing filters: " + e.getMessage());
            System.exit(1);
        }
    }
}
