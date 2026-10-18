package pt.ulisboa.tecnico.cnv.common.metrics;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Data access object for updating aggregate metrics in DynamoDB.
 * 
 * Uses atomic UpdateItem operations with ADD to increment counters.
 * This avoids read-modify-write races under concurrent updates.
 * Multiple workers can safely update the same bucket simultaneously.
 */
public class DynamoAggregateMetricsDao {

    private static final String TABLE_NAME = System.getenv().getOrDefault(
            "NATURE_METRICS_AGG_TABLE", "NatureMetricsAgg");

    private final DynamoDbClient dynamoDbClient;

    public DynamoAggregateMetricsDao(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Update aggregate metrics for a bucketed parameter set.
     * 
     * Atomically increments:
     *   - Memoryscore
     *   - sumDurationMs
     *   - samples (count of requests)
     * 
     * Also updates lastUpdated timestamp.
     * 
     * @param metrics the request metrics to aggregate
     * @param parameterBucket the bucketed parameter string (e.g., "fractals#w8-h6-i2")
     * @return true if update succeeded, false on error
     */
    public boolean updateAggregateMetrics(RequestMetrics metrics, String parameterBucket) {
        if (metrics == null || parameterBucket == null) {
            return false;
        }

        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("operation", AttributeValue.builder()
            .s(metrics.operation)
            .build());

            key.put("parameterBucket", AttributeValue.builder()
            .s(parameterBucket)
            .build());

            // Update expression: atomically add metrics and set timestamp
            String updateExpression = "ADD sumMemoryScore :mem, sumDurationMs :dur, samples :one " +
                                    "SET lastUpdated = :now";

            // Attribute values for the update
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":mem", AttributeValue.builder()
                    .n(String.valueOf(metrics.memoryScore))
                    .build());
            expressionAttributeValues.put(":dur", AttributeValue.builder()
                    .n(String.valueOf(metrics.durationMs()))
                    .build());
            expressionAttributeValues.put(":one", AttributeValue.builder()
                    .n("1")
                    .build());
            expressionAttributeValues.put(":now", AttributeValue.builder()
                    .n(String.valueOf(System.currentTimeMillis()))
                    .build());

            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(key)
                    .updateExpression(updateExpression)
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();

            UpdateItemResponse response = dynamoDbClient.updateItem(updateRequest);

            return response.sdkHttpResponse().isSuccessful();
        } catch (Exception e) {
            System.err.println("[DynamoAggregateMetricsDao] Failed to update aggregate metrics: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
