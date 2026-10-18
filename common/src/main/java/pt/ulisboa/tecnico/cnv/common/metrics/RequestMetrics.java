package pt.ulisboa.tecnico.cnv.common.metrics;

public class RequestMetrics {
    public final String requestId;
    public final String operation;
    public final String params;
    public final long startTimeMs;

    public long endTimeMs;
    public long basicBlocks;
    public long instructions;
    public long memoryScore;

    public String parameterBucket;

    public RequestMetrics(String requestId, String operation, String params) {
        this.requestId = requestId;
        this.operation = operation;
        this.params = params;
        this.startTimeMs = System.currentTimeMillis();
        this.memoryScore = 0;
    }

    public long durationMs() {
        if (endTimeMs == 0) {
            return System.currentTimeMillis() - startTimeMs;
        }
        return endTimeMs - startTimeMs;
    }
}
