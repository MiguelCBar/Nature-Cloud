package pt.ulisboa.tecnico.cnv.common.metrics;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Orchestration service for writing metrics to DynamoDB.
 * 
 * Handles both raw request logging and aggregate metrics updates.
 * Called at the end of each request to persist metrics without blocking the response.
 */
public class DynamoMetricsService {

    private final DynamoRequestLogDao rawLogDao;
    private final DynamoAggregateMetricsDao aggregateDao;

    public DynamoMetricsService(DynamoDbClient dynamoDbClient) {
        this.rawLogDao = new DynamoRequestLogDao(dynamoDbClient);
        this.aggregateDao = new DynamoAggregateMetricsDao(dynamoDbClient);
    }

    /**
     * Record metrics for a finished request.
     * 
     * This method:
     *   1. Extracts the request parameters from the metrics
     *   2. Computes the bucket key
     *   3. Writes raw log entry
     *   4. Updates aggregate metrics
     * 
     * Note: For brevity, this version infers the operation type from metrics.operation
     * and does NOT bucket the parameters (assumes raw params are passed to bucket methods).
     * 
     * @param metrics the completed request metrics
     * @param parameterBucket pre-computed bucket string (e.g., "fractals#w8-h6-i2")
     */
    public void record(RequestMetrics metrics, String parameterBucket) {
        if (metrics == null || parameterBucket == null) {
            return;
        }

        boolean rawSuccess =
                rawLogDao.writeRawLog(metrics, parameterBucket);

        if (!rawSuccess) {
            System.err.println(
                "[DynamoMetricsService] Failed raw log write"
            );
        }

        boolean aggSuccess =
                aggregateDao.updateAggregateMetrics(metrics, parameterBucket);

        if (!aggSuccess) {
            System.err.println(
                "[DynamoMetricsService] Failed aggregate update"
            );
        }
    }
}