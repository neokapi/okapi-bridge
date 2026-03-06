package com.gokapi.bridge;

import com.gokapi.bridge.grpc.BridgeServiceImpl;
import com.gokapi.bridge.io.LocalContentResolver;
import com.gokapi.bridge.io.LocalOutputWriter;
import com.gokapi.bridge.model.FilterInfo;
import com.gokapi.bridge.util.FilterRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the Okapi Bridge gRPC server.
 * Starts a gRPC server on a random port and prints the address to stdout
 * so the Go client can connect. All logging goes to stderr.
 *
 * <p>Flags:
 * <ul>
 *   <li>{@code --list-filters} — Print filter metadata as JSON to stdout and exit.</li>
 * </ul>
 */
public class OkapiBridgeServer {

    public static void main(String[] args) {
        // Handle --list-filters: output filter info as JSON and exit (no gRPC server).
        for (String arg : args) {
            if ("--list-filters".equals(arg)) {
                listFiltersAndExit();
                return;
            }
        }

        System.err.println("[bridge] Okapi Bridge Server starting (gRPC)...");

        try {
            BridgeServiceImpl service = new BridgeServiceImpl(
                    new LocalContentResolver(), new LocalOutputWriter());

            Server server = ServerBuilder.forPort(0) // random available port
                    .addService(service)
                    .maxInboundMessageSize(64 * 1024 * 1024)
                    .build()
                    .start();

            int port = server.getPort();
            String address = "localhost:" + port;

            System.err.println("[bridge] gRPC server started on " + address);

            // Print address to stdout — the Go client reads this first line.
            System.out.println(address);
            System.out.flush();

            // Wait for shutdown signal from the client.
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
     * Print all discovered filters as a JSON object to stdout, then exit.
     * Output format: {@code {"filters":[{"name":"...","display_name":"...","mime_types":[...],"extensions":[...]},...]}}.
     */
    private static void listFiltersAndExit() {
        try {
            List<FilterInfo> filters = FilterRegistry.listFilters();
            Gson gson = new GsonBuilder().create();
            // Wrap in {"filters":[...]} envelope for the release workflow.
            System.out.println("{\"filters\":" + gson.toJson(filters) + "}");
        } catch (Exception e) {
            System.err.println("[bridge] Error listing filters: " + e.getMessage());
            System.exit(1);
        }
    }
}
