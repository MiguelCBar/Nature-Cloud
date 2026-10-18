package pt.ulisboa.tecnico.cnv.master.config;

import java.io.InputStream;
import java.util.Properties;

public class AutoScalerConfig {
    private static final Properties properties = new Properties();

    static {
        try (InputStream input = AutoScalerConfig.class.getClassLoader()
                                                 .getResourceAsStream("autoscaler.properties")) {
            if (input == null) 
                throw new RuntimeException("Missing autoscaler.properties!");
            properties.load(input);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    // Interval between load checks (in seconds)
    public static int getCheckIntervalSeconds() { 
        return Integer.parseInt(properties.getProperty("checkIntervalSeconds", "60").trim()); 
    }
    
    // Minimum and Maximum number of worker instances to maintain
    public static int getMinInstances() { 
        return Integer.parseInt(properties.getProperty("minInstances", "1").trim()); 
    }
    public static int getMaxInstances() { 
        return Integer.parseInt(properties.getProperty("maxInstances", "5").trim()); 
    }

    // CloudWatch CPU %
    public static double getUpperCpuThreshold() { 
        return Double.parseDouble(properties.getProperty("upperCpuThreshold", "80.0").trim()); 
    }
    public static double getLowerCpuThreshold() { 
        return Double.parseDouble(properties.getProperty("lowerCpuThreshold", "30.0").trim()); 
    }

    // Warmup period for new READY nodes before their CPU metrics are considered valid
    public static long getCloudWatchWarmupSeconds() {
        return Long.parseLong(properties.getProperty("cloudWatchWarmupSeconds", "60").trim());
    }

    // Critical Step Scaling Threshold
    public static double getCriticalCpuThresholdUp() { 
        return Double.parseDouble(properties.getProperty("criticalCpuThreshold", "90.0").trim()); 
    }

    //Critical Scaling Step Size
    public static int getCriticalStepSize() {
        return Integer.parseInt(properties.getProperty("criticalStepSize", "2").trim());
    }
    
    // Last N minutes of CloudWatch data to consider for load evaluation
    public static int getCloudWatchWindowMinutes() {
        return Integer.parseInt(properties.getProperty("cloudWatchWindowMinutes", "3").trim());
    }
    // CloudWatch data retrieval period (in seconds)
    public static int getCloudWatchPeriodSeconds() {
        return Integer.parseInt(properties.getProperty("cloudWatchPeriodSeconds", "60").trim());
    }

    public static int getBootGracePeriodSeconds() {
        return Integer.parseInt(properties.getProperty("bootGracePeriodSeconds", "600").trim());
    }

    public static int getMaxPublicIpAttempts() {
        return Integer.parseInt(properties.getProperty("maxPublicIpAttempts", "10").trim());
    }

    public static int getSleepSecondsBetweenIpAttempts() {
        return Integer.parseInt(properties.getProperty("sleepSecondsBetweenIpAttempts", "5").trim());
    }

    public static int getMaxHealthCheckTries() {
        return Integer.parseInt(properties.getProperty("maxHealthCheckTries", "3").trim());
    }
}
