package pt.ulisboa.tecnico.cnv.webserver;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;

import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.dna.DnaHandler;
import pt.ulisboa.tecnico.cnv.fractals.FractalsHandler;
import pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler;

import pt.ulisboa.tecnico.cnv.common.metrics.Metrics;
import pt.ulisboa.tecnico.cnv.common.metrics.DynamoMetricsService;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class WebServer {


    // Initialize DynamoDB client and service once (in your WebServer.main())
    // This is a singleton pattern
    private static pt.ulisboa.tecnico.cnv.common.metrics.DynamoMetricsService dynamoService;

    /**
     * Call this once during application startup (e.g., in WebServer.main())
     */
    public static void initializeDynamoMetrics() {
        DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .build();
        dynamoService = new DynamoMetricsService(dynamoDbClient);
        Metrics.setDynamoService(dynamoService);
    }

    public static void main(String[] args) throws Exception {


	
	int port = 8000;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());

	server.createContext("/health", exchange -> {
            String response = "OK\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        initializeDynamoMetrics();
        server.createContext("/fractals", new FractalsHandler());
        server.createContext("/dna", new DnaHandler());
        server.createContext("/grayscott", new GrayScottHandler());
        server.start();

	System.out.println("===================================");
        System.out.println("Nature@Cloud WebServer started");
        System.out.println("Listening on port " + port);
        System.out.println("Test with:");
        System.out.println("  curl http://localhost:" + port + "/health");
        System.out.println("  curl http://localhost:" + port + "/");
        System.out.println("===================================");

    }
}
