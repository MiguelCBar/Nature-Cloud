package pt.ulisboa.tecnico.cnv.master.config;

import java.io.InputStream;
import java.util.Properties;

public class LoadBalancerConfig {
    private static final Properties properties = new Properties();

    static {
        // Load properties from the classpath
        try (InputStream input = LoadBalancerConfig.class.getClassLoader()
                                                 .getResourceAsStream("loadbalancer.properties")) {
            if (input == null) {
                throw new RuntimeException("Missing loadbalancer.properties!");
            }
            properties.load(input);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    public static int getPort() {
        return Integer.parseInt(properties.getProperty("port", "8000"));
    }

    public static String getHealthCheckPath() {
        return properties.getProperty("healthCheckPath", "/");
    }

    // Get the list of exact paths the LB is allowed to proxy
    public static String[] getWorkloadPaths() {
        String paths = properties.getProperty("workloadPaths", "/fractals,/dna,/grayscott");
        return paths.split(",");
    }

    public static int getProxyConnectTimeout() {
        return Integer.parseInt(properties.getProperty("proxy.connect.timeout.ms", "5000"));
    }

    public static int getProxyReadTimeout() {
        return Integer.parseInt(properties.getProperty("proxy.read.timeout.ms", "120000"));
    }

    public static int getMaxRetryAttempts() {
        return Integer.parseInt(properties.getProperty("lb.retry.max.attempts", "3"));
    }

    public static int getThreadPoolSize() {
        return Integer.parseInt(properties.getProperty("lb.thread.pool.size", "100"));
    }
    
    /**
     * The cost threshold below which a request is considered "Small" and eligible for Lambda Spillover.
     */
    public static long getLambdaMaxCost() {
        return Long.parseLong(properties.getProperty("lb.lambda.max.cost", "1500000"));
    }

    public static long getMaxLoadPerNode() {
        return Long.parseLong(properties.getProperty("lb.max.allowed.load.per.node", "10000000"));
    }

    public static long getScaleInSafetyMargin() {
        return Long.parseLong(properties.getProperty("lb.scale.in.safety.margin", "10000000"));
    }
}