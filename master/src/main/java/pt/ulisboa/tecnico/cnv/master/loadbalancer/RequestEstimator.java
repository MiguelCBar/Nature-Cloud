package pt.ulisboa.tecnico.cnv.master.loadbalancer;

import pt.ulisboa.tecnico.cnv.common.config.AwsConfig;
import pt.ulisboa.tecnico.cnv.common.metrics.MetricBucketer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estimates request complexity for load balancing.
 *
 * Flow:
 *   1. parse operation and params
 *   2. build parameter bucket
 *   3. check TTL cache
 *   4. query aggregate DynamoDB table
 *   5. fallback heuristic if no data exists
 *
 * IMPORTANT:
 * Keep the load-balancer unit consistent everywhere.
 * This implementation uses "predicted duration-like cost" as the routing unit.
 */
public class RequestEstimator {

    private static final String DEFAULT_AGG_TABLE =
            System.getenv().getOrDefault("NATURE_METRICS_AGG_TABLE", "NatureMetricsAgg");

    private static final long DEFAULT_CACHE_TTL_MS = 5_000L;
    private static final long DEFAULT_TIMER_MULTIPLIER = 1L;
    private static final long MAX_CACHE_TTL_MS = 300_000L;

    private final DynamoDbClient dynamoDbClient;
    private final String aggregateTableName;
    private final long cacheTtlMs;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public RequestEstimator() {
        Region region = Region.of(AwsConfig.getRegion());
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(region)
                .build();

        this.aggregateTableName = DEFAULT_AGG_TABLE;
        this.cacheTtlMs = parseLongEnv("LB_ESTIMATE_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS);
    }

    /**
     * Main entry point used by the Load Balancer.
     */
    public long estimateComplexity(String path, String query) {
        String operation = normalizeOperation(path);
        Map<String, String> params = parseQuery(query);
        String parameterBucket = buildParameterBucket(operation, params);

        String cacheKey = parameterBucket;

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isFresh(cacheTtlMs)) {
            //System.out.println("[LB ESTIMATOR] CACHE HIT for " + cacheKey + " => " + cached.predictedCost);
            cached.touch(); // refresh TTL on access
            return cached.predictedCost;
        }

        if (cached != null) {
            cache.remove(cacheKey);
        }

        CacheEntry dbEntry = lookupAggregateEstimate(operation, parameterBucket);
        if (dbEntry != null) {
            cache.put(cacheKey, dbEntry);
            //System.out.println("[LB ESTIMATOR] DDB HIT for " + cacheKey + " => " + dbEntry.predictedCost);
            return dbEntry.predictedCost;
        }

        long fallback = fallbackEstimate(operation, params);
        cache.put(cacheKey, new CacheEntry(
                fallback,
                DEFAULT_TIMER_MULTIPLIER,
                System.currentTimeMillis()
        ));

        //System.out.println("[LB ESTIMATOR] FALLBACK for " + cacheKey + " => " + fallback);
        return fallback;
    }

    private CacheEntry lookupAggregateEstimate(String operation, String parameterBucket) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("operation", AttributeValue.builder().s(operation).build());
            key.put("parameterBucket", AttributeValue.builder().s(parameterBucket).build());

            GetItemRequest request = GetItemRequest.builder()
                    .tableName(aggregateTableName)
                    .key(key)
                    .consistentRead(true)
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(request);

            if (!response.hasItem()) {
                return null;
            }

            Map<String, AttributeValue> item = response.item();

            long samples = getLong(item, "samples", 0L);
            if (samples <= 0) {
                return null;
            }
            
            long sumMemoryScore = getLong(item, "sumMemoryScore", 0L);

            double avgMemoryScore = sumMemoryScore / (double) samples;

            /*
            * First live version:
            * use average memory score as the load-balancing unit.
            *
            * This keeps the unit intuitive and stable:
            * the worker's assignedWork and the LB's predicted cost
            * are in the same scale.
            */
            long predictedCost = (long) avgMemoryScore;

            return new CacheEntry(
                    predictedCost,
                    DEFAULT_TIMER_MULTIPLIER,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            System.err.println("[RequestEstimator] DynamoDB lookup failed for "
                    + operation + "#" + parameterBucket + ": " + e.getMessage());
            return null;
        }
    }

    private String buildParameterBucket(String operation, Map<String, String> params) {
        switch (operation) {
            case "fractals": {
                int w = getInt(params, "w", 512);
                int h = getInt(params, "h", 512);
                int iterations = getInt(params, "iterations", 100);
                return MetricBucketer.bucketFractals(w, h, iterations);
            }

            case "dna": {
                String seq1 = params.getOrDefault("seq1", "seq1:ATGC");
                String seq2 = params.getOrDefault("seq2", "seq2:ATGC");
                int minLength = getInt(params, "minLength", 1);
                boolean stopOnFirst = getBoolean(params, "stopOnFirst", false);
                return MetricBucketer.bucketDna(seq1, seq2, minLength, stopOnFirst);
            }

            case "grayscott": {
                int size = getInt(params, "size", 256);
                int maxIterations = getInt(params, "maxIterations", 5000);
                double f = getDouble(params, "f", 0.030);
                double k = getDouble(params, "k", 0.062);
                boolean stopOnExtinction = getBoolean(params, "stopOnExtinction", false);
                String seedMode = params.getOrDefault("seedMode", "center");
                return MetricBucketer.bucketGrayScott(size, maxIterations, f, k, stopOnExtinction, seedMode);
            }

            default:
                return operation + "#unknown";
        }
    }

    private long fallbackEstimate(String operation, Map<String, String> params) {
        switch (operation) {
            case "fractals": {
                long w = getInt(params, "w", 512);
                long h = getInt(params, "h", 512);
                long iterations = getInt(params, "iterations", 100);

                long feature = Math.max(1L, w * h * iterations);

                // memoryScore ≈ 3446.954837 * feature^0.486158
                double estimate = 3446.954837 * Math.pow((double) feature, 0.486158);
                return Math.max(1L, Math.round(estimate));
            }

            case "dna": {
            String seq1 = params.getOrDefault("seq1", "seq1:ATGC");
            String seq2 = params.getOrDefault("seq2", "seq2:ATGC");
            int minLength = getInt(params, "minLength", 1);
            boolean stopOnFirst = getBoolean(params, "stopOnFirst", false);

            long l1 = seqLengthFromParam(seq1);
            long l2 = seqLengthFromParam(seq2);

            double estimate =
                    500.0
                  + 18.0 * l1
                  + 16.0 * l2
                  + 0.20 * (l1 * l2)
                  + 35.0 * minLength
                  - (stopOnFirst ? 250.0 : 0.0);

            return Math.max(1L, Math.round(estimate));
            }

            case "grayscott": {
                long size = getInt(params, "size", 256);
                long maxIterations = getInt(params, "maxIterations", 5000);

                long feature = Math.max(1L, size * size * maxIterations);

                // divisor fit: memoryScore ≈ feature / 0.029360
                double estimate = ((double) feature) / 0.029360;
                return Math.max(1L, Math.round(estimate));
            }

            default:
                return 1000L;
        }
    }

    private long seqLengthFromParam(String value) {
        if (value == null || value.isEmpty()) return 0L;
        String s = value;
        int idx = s.indexOf(':');
        if (idx >= 0) {
            s = s.substring(idx + 1);
        }
        return s.replaceAll("[^A-Za-z]", "").length();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isBlank()) {
            return result;
        }

        String[] parts = query.split("&");
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }

            String[] kv = part.split("=", 2);
            String key = urlDecode(kv[0]);
            String value = kv.length > 1 ? urlDecode(kv[1]) : "";
            result.put(key, value);
        }

        return result;
    }

    private String normalizeOperation(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }

        String op = path.trim().toLowerCase(Locale.ROOT);
        if (op.startsWith("/")) {
            op = op.substring(1);
        }

        // Keep only the first path segment
        int slash = op.indexOf('/');
        if (slash >= 0) {
            op = op.substring(0, slash);
        }

        if (op.isBlank()) {
            return "unknown";
        }

        return op;
    }

    private String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private int getInt(Map<String, String> params, String key, int defaultValue) {
        try {
            String value = params.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double getDouble(Map<String, String> params, String key, double defaultValue) {
        try {
            String value = params.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Double.parseDouble(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean getBoolean(Map<String, String> params, String key, boolean defaultValue) {
        try {
            String value = params.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Boolean.parseBoolean(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private long getLong(Map<String, AttributeValue> item, String key, long defaultValue) {
        try {
            AttributeValue value = item.get(key);
            if (value == null || value.n() == null || value.n().isBlank()) {
                return defaultValue;
            }
            return Long.parseLong(value.n());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private long parseLongEnv(String name, long defaultValue) {
        try {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Long.parseLong(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static final class CacheEntry {
    final long predictedCost;
    long cachedAtMs;
    long timermultiplier;

        CacheEntry(long predictedCost, long timermultiplier, long cachedAtMs) {
            this.predictedCost = predictedCost;
            this.timermultiplier = Math.max(1L, timermultiplier);
            this.cachedAtMs = cachedAtMs;
        }


        // Sliding TTL cache:
        // each hit refreshes the timestamp and can extend the lifetime
        // up to a capped maximum TTL.
        void touch() {
            cachedAtMs = System.currentTimeMillis();
            if (timermultiplier * DEFAULT_CACHE_TTL_MS <= MAX_CACHE_TTL_MS) {
                timermultiplier++;
            }
        }

        boolean isFresh(long baseTtlMs) {
            long effectiveTtl = baseTtlMs * timermultiplier;
            if (effectiveTtl > MAX_CACHE_TTL_MS) {
                effectiveTtl = MAX_CACHE_TTL_MS;
            }
            return (System.currentTimeMillis() - cachedAtMs) < effectiveTtl;
        }
    }
}