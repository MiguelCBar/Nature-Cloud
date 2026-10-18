package pt.ulisboa.tecnico.cnv.common.metrics;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Metrics {

    private static final ThreadLocal<RequestMetrics> current = new ThreadLocal<>();
    
    private static volatile DynamoMetricsService dynamoService;
    private static final ExecutorService DYNAMO_EXECUTOR =
        Executors.newSingleThreadExecutor();
        

    public static String startRequest(String operation, String params) {
        String requestId = UUID.randomUUID().toString();

        RequestMetrics metrics = new RequestMetrics(requestId, operation, params);
        current.set(metrics);

        return requestId;
    }

    /**
     * Adds weighted memory pressure to the current request.
     */
    public static void countMemory(long memoryWeight) {
        RequestMetrics metrics = current.get();
        if (metrics == null) {
            return;
        }
        metrics.memoryScore += memoryWeight;
    }

    public static RequestMetrics endRequest() {
        RequestMetrics metrics = current.get();

        if (metrics == null) {
            return null;
        }

        metrics.endTimeMs = System.currentTimeMillis();
        
        // Try to write to DynamoDB if service is available
        if (dynamoService != null && metrics.parameterBucket != null) {
            DYNAMO_EXECUTOR.submit(() -> {
                try {
                    dynamoService.record(metrics, metrics.parameterBucket);
                } catch (Exception e) {
                    System.err.println("[Metrics] Failed to record metrics in DynamoDB: " + e.getMessage());
                }
            });
        }

        current.remove();
        return metrics;
    }

    public static RequestMetrics getCurrent() {
        return current.get();
    }

    /**
     * Set the DynamoDB metrics service.
     * Call this once during application initialization.
     * Uses Object type to avoid compile-time dependency on worker module.
     * 
     * @param service the DynamoMetricsService instance
     */
    public static void setDynamoService(DynamoMetricsService service) {
        dynamoService = service;
    }

    /**
     * Store the bucketed parameter key for the current request.
     * Call this after computing the bucket key but before request processing.
     * 
     * @param bucketKey the bucket string (e.g., "fractals#w8-h6-i2")
     */
    public static void setCurrentBucketKey(String bucketKey) {
        RequestMetrics metrics = current.get();
        if (metrics != null) {
            metrics.parameterBucket = bucketKey;
        }
    }

    public static void shutdown() {
        DYNAMO_EXECUTOR.shutdown();
    }

}
