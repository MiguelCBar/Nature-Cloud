package pt.ulisboa.tecnico.cnv.common.metrics;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Data access object for writing raw request logs to DynamoDB.
 * 
 * Writes one item per finished request to the NatureRequestLog table.
 * This is useful for debugging, proof of execution, and later export.
 */
public class DynamoRequestLogDao {

    private static final String TABLE_NAME = System.getenv().getOrDefault(
            "NATURE_REQUEST_LOG_TABLE", "NatureRequestLog");

    private final DynamoDbClient dynamoDbClient;

    public DynamoRequestLogDao(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Write a raw request log entry to DynamoDB.
     * 
     * @param metrics the request metrics containing memoryScore, duration
     * @param parameterBucket the bucketed parameter string (e.g., "fractals#w8-h6-i2")
     * @return true if write succeeded, false on error
     */
    public boolean writeRawLog(RequestMetrics metrics, String parameterBucket) {
        if (metrics == null || parameterBucket == null) {
            return false;
        }

        try {
            Map<String, AttributeValue> item = new HashMap<>();

            // Key
            item.put("requestId", AttributeValue.builder()
                    .s(metrics.requestId)
                    .build());

            // Metadata
            item.put("operation", AttributeValue.builder()
                    .s(metrics.operation)
                    .build());

            item.put("parameterBucket", AttributeValue.builder()
                    .s(parameterBucket)
                    .build());

            item.put("rawParams", AttributeValue.builder()
                    .s(metrics.params)
                    .build());

            // Execution metrics
            item.put("memoryScore", AttributeValue.builder()
                    .n(String.valueOf(metrics.memoryScore))
                    .build());
            item.put("durationMs", AttributeValue.builder()
                    .n(String.valueOf(metrics.durationMs()))
                    .build());

            // Timestamp
            item.put("timestamp", AttributeValue.builder()
                    .n(String.valueOf(System.currentTimeMillis()))
                    .build());

            PutItemRequest putRequest = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .build();

            PutItemResponse response = dynamoDbClient.putItem(putRequest);

            return response.sdkHttpResponse().isSuccessful();
        } catch (Exception e) {
            System.err.println("[DynamoRequestLogDao] Failed to write raw log: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
