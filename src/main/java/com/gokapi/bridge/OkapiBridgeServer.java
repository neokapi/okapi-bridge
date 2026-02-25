package com.gokapi.bridge;

import com.gokapi.bridge.grpc.BridgeServiceImpl;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the Okapi Bridge gRPC server.
 * Starts a gRPC server on a random port and prints the address to stdout
 * so the Go client can connect. All logging goes to stderr.
 */
public class OkapiBridgeServer {

    public static void main(String[] args) {
        System.err.println("[bridge] Okapi Bridge Server starting (gRPC)...");

        try {
            BridgeServiceImpl service = new BridgeServiceImpl();

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
}
