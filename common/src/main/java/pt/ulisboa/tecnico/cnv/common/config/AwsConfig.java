package pt.ulisboa.tecnico.cnv.common.config;

import java.io.InputStream;
import java.util.Properties;

public class AwsConfig {
    private static final Properties properties = new Properties();

    static {
        try (InputStream input = AwsConfig.class.getClassLoader()
                                          .getResourceAsStream("aws.properties")) {
            if (input == null) 
                throw new RuntimeException("Missing aws.properties!");
            properties.load(input);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    public static String getRegion() { 
        return properties.getProperty("region", "us-east-1"); 
    }

    public static String getSecurityGroup() { 
        return properties.getProperty("securityGroup"); 
    }

    public static String getKeyPairName() { 
        return properties.getProperty("keyPairName"); 
    }

    public static String getLaunchTemplateName() {
        return properties.getProperty("launchTemplateName", "CNV-LaunchTemplate"); 
    }
}
