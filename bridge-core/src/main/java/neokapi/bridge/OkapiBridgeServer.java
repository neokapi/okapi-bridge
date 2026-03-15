package neokapi.bridge;

import neokapi.bridge.grpc.BridgeServiceImpl;
import neokapi.bridge.io.LocalContentResolver;
import neokapi.bridge.io.LocalOutputWriter;
import neokapi.bridge.model.FilterInfo;
import neokapi.bridge.util.FilterRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

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
 * </ul>
 */
public class OkapiBridgeServer {

    public static void main(String[] args) {
        long idleTimeoutSeconds = 0;

        for (int i = 0; i < args.length; i++) {
            if ("--list-filters".equals(args[i])) {
                listFiltersAndExit();
                return;
            }
            if ("--idle-timeout".equals(args[i]) && i + 1 < args.length) {
                idleTimeoutSeconds = parseTimeout(args[++i]);
            }
        }

        System.err.println("[bridge] Okapi Bridge Server starting (gRPC)...");

        try {
            BridgeServiceImpl service = new BridgeServiceImpl(
                    new LocalContentResolver(), new LocalOutputWriter(), idleTimeoutSeconds);

            Server server = ServerBuilder.forPort(0) // random available port
                    .addService(service)
                    .maxInboundMessageSize(64 * 1024 * 1024)
                    .build()
                    .start();

            int port = server.getPort();
            String address = "localhost:" + port;

            System.err.println("[bridge] gRPC server started on " + address);
            if (idleTimeoutSeconds > 0) {
                System.err.println("[bridge] Idle timeout: " + idleTimeoutSeconds + "s");
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
            System.err.println("[bridge] Invalid --idle-timeout value: " + value);
            return 0;
        }
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
