package pt.ulisboa.tecnico.cnv.master.autoscaler;

import pt.ulisboa.tecnico.cnv.master.config.AutoScalerConfig;
import pt.ulisboa.tecnico.cnv.master.config.LoadBalancerConfig;

public class ScalingPolicy {

    private static final long MAX_LOAD_PER_NODE = LoadBalancerConfig.getMaxLoadPerNode(); 

    /**
     * Emergency Routine: Triggered by LB or HM when they detect a critical event
     * Ignores CPU completely.
     */

    public int calculateEmergencyTarget(int currentDesired, long totalDemand, 
                                        long readyCount, long bootingCount) {
        
        long requiredNodes = (totalDemand + MAX_LOAD_PER_NODE) / MAX_LOAD_PER_NODE;
        requiredNodes = Math.max(requiredNodes, AutoScalerConfig.getMinInstances());
        
        long totalFutureNodes = readyCount + bootingCount;
        int newCapacity = currentDesired;

        // In an emergency, we ONLY care about scaling up if we lack power.
        if (requiredNodes > currentDesired) {
            
            int maxConcurrentBoots = AutoScalerConfig.getCriticalStepSize();
            if (bootingCount >= maxConcurrentBoots) {
                System.out.println("[Policy] Emergency Scale Up BLOCKED. Maximum concurrent booting nodes reached (" 
                                   + bootingCount + "). Waiting for them to become READY.");
                return currentDesired;
            }

            // Calculate exactly how many we are allowed to boot right now
            int allowedNewBoots = maxConcurrentBoots - (int) bootingCount;
            
            // Determine the increment
            int increment = Math.min((int) (requiredNodes - totalFutureNodes), maxConcurrentBoots);
            increment = Math.min(increment, allowedNewBoots); // Enforce the cap

            if (increment > 0) {
                newCapacity += increment;
                System.out.println("[Policy] Emergency Scale Up by " + increment);
            }
            
        } else {
            System.out.println("[Emergency Wake Up] Does not require scaling. Maintain.");
        }

        return Math.min(newCapacity, AutoScalerConfig.getMaxInstances());
    }

    /**
     * Default Routine: Regular checks to evaluate cluster health and load,
     * Evaluates Queue for standard scaling, AND evaluates CPU for silent hardware meltdowns.
     */
    public int calculateDefaultTarget(int currentDesired, double clusterAverageCpu, 
                                       long totalDemand, long readyCount, long bootingCount) {
        
        long totalFutureNodes = readyCount + bootingCount;
        
        long requiredNodes = (totalDemand + MAX_LOAD_PER_NODE) / MAX_LOAD_PER_NODE;
        requiredNodes = Math.max(requiredNodes, AutoScalerConfig.getMinInstances());
        
        long nodeDifference = requiredNodes - currentDesired;
        
        System.out.println("[Policy] CPU: " + clusterAverageCpu + "% | Queue Requires: " 
                           + requiredNodes + " | Future Nodes: " + totalFutureNodes);

        LoadTier currentTier = LoadTier.evaluate(clusterAverageCpu, nodeDifference);
        int newCapacity = currentDesired;

        switch (currentTier) {
            
            case CRITICAL_HIGH:
                int maxBoots = AutoScalerConfig.getCriticalStepSize();
                System.out.println("DECISION: CRITICAL SCALE UP\n" + 
                                   "REASON: Massive traffic spike or high CPU usage!" + 
                                   " Target + " + maxBoots);
                newCapacity += maxBoots;
                break;

            case HIGH:
                System.out.println("DECISION: NORMAL SCALE UP\n" + 
                                   "REASON: High load detected. Target + 1");
                newCapacity += 1;
                break;

            case LOW:
                if (bootingCount > 0) {
                    System.out.println("DECISION: MAINTAIN\n" + 
                                       "REASON: Excess capacity detected, but blocked NORMAL SCALE DOWN because nodes are currently BOOTING.");
                    break;
                }
                if (currentDesired <= AutoScalerConfig.getMinInstances()) {
                    System.out.println("DECISION: MAINTAIN\n" + 
                                       "REASON: Excess capacity detected, but already at minimum cluster capacity.");
                    break;
                }
                
                System.out.println("DECISION: NORMAL SCALE IN\n" + 
                                   "REASON: Excess capacity and CPU is cool. Target - 1");
                newCapacity -= 1;
                break;
                
            default:
                System.out.println("DECISION: MAINTAIN\n" + 
                                   "REASON: Cluster load is perfectly balanced.");
                break;
        }

        if (newCapacity > AutoScalerConfig.getMaxInstances()) {
            newCapacity = AutoScalerConfig.getMaxInstances();
            System.out.println("[POLICY LIMIT] Max instances reached. Capping at " + 
                                newCapacity);
        } else if (newCapacity < AutoScalerConfig.getMinInstances()) {
            newCapacity = AutoScalerConfig.getMinInstances();
            System.out.println("[POLICY LIMIT] Min instances reached. Flooring at " + 
                                newCapacity);
        }

        return newCapacity;
    }
}
