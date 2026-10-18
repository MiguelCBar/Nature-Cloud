package pt.ulisboa.tecnico.cnv.master.autoscaler;

import pt.ulisboa.tecnico.cnv.master.config.AutoScalerConfig;

public enum LoadTier {
    CRITICAL_HIGH,
    HIGH,
    LOW,
    BALANCED;

    /**
     * Evaluates the load tier by combining strict Queue Math with physical CPU thresholds.
     * @param cpu The average CPU usage across READY nodes.
     * @param nodeDifference The difference between required nodes and future power.
     */
    public static LoadTier evaluate(double cpu, long nodeDifference) {
        
        // CRITICAL HIGH
        if (cpu > AutoScalerConfig.getCriticalCpuThresholdUp() || 
            nodeDifference >= AutoScalerConfig.getCriticalStepSize()) {
            return CRITICAL_HIGH;
        }
        
        // HIGH
        if (cpu > AutoScalerConfig.getUpperCpuThreshold() || 
            nodeDifference > 0) {
            return HIGH;
        }
        
        // LOW
        if (cpu < AutoScalerConfig.getLowerCpuThreshold() && 
            nodeDifference < 0) {
            return LOW;
        }
        
        // BALANCED
        return BALANCED;
    }
}