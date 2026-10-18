package pt.ulisboa.tecnico.cnv.master.config;

import java.io.InputStream;
import java.util.Properties;

public class HealthManagerConfig {
        private static final Properties properties = new Properties();

    static {
        try (InputStream input = HealthManagerConfig.class.getClassLoader()
                                                 .getResourceAsStream("healthmanager.properties")) {
            if (input == null) 
                throw new RuntimeException("Missing healthmanager.properties!");
            properties.load(input);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    // Interval between health checks (in seconds)
    public static int getCheckIntervalSeconds() { 
        return Integer.parseInt(properties.getProperty("healthmanager.checkInterval.seconds", "5").trim()); 
    }
}
