package pt.ulisboa.tecnico.cnv.master.autoscaler;

import pt.ulisboa.tecnico.cnv.master.cloud.AwsCloudClient;
import pt.ulisboa.tecnico.cnv.master.config.AutoScalerConfig;
import pt.ulisboa.tecnico.cnv.master.state.ClusterState;
import pt.ulisboa.tecnico.cnv.master.state.WorkerNode;

import java.util.List;

public class AutoScalerDaemon extends Thread {

    private final AwsCloudClient awsClient;
    private final ClusterState clusterState;
    private final ScalingPolicy scalingPolicy;
    private final CapacityController capacityController;

    private int desiredCapacity;
    
    // Lock object to auxiliate LB and HM wakeup triggers and AS default routine
    private final Object lock = new Object();

    // Triggered by LB and HM to signal an emergency scaling event. Protected by cooldown logic.
    private volatile boolean emergencyWakeupRequested = false;

    public AutoScalerDaemon() {
        this.awsClient = new AwsCloudClient();
        this.clusterState = ClusterState.getInstance();
        this.desiredCapacity = AutoScalerConfig.getMinInstances(); // Start with minimum capacity
        this.scalingPolicy = new ScalingPolicy();
        this.capacityController = new CapacityController(awsClient, clusterState);
    }

    @Override
    public void run() {
        System.out.println("[AutoScaler] Thread started. Entering event-driven loop.");
        
        while (true) {
            try {
                // Local flag to determine which routine to execute, acts as a snapshot
                boolean isEmergencyCycle;
                synchronized (lock) {
                    // Read the global flag (Take the snapshot)
                    isEmergencyCycle = this.emergencyWakeupRequested;
                    // Set to false immediately to consume the signal
                    this.emergencyWakeupRequested = false; 
                }

                if (isEmergencyCycle) {
                    System.out.println("\n[AutoScaler] === EMERGENCY TICK Initiated ===");
                    this.executeEmergencyRoutine();
                } else {
                    System.out.println("\n[AutoScaler] === STANDARD TICK Initiated ===");
                    this.executeDefaultRoutine();
                }

                synchronized (lock) {
                    // If a new emergency arrived while we were executing the routine, the flag 
                    // is true again. We skip sleep. Otherwise, we wait.
                    if (!this.emergencyWakeupRequested) {
                        long sleepMs = AutoScalerConfig.getCheckIntervalSeconds() * 1000L;
                        lock.wait(sleepMs); 
                    }
                }

            } catch (InterruptedException e) {
                System.out.println("[AutoScaler] Thread interrupted. Shutting down daemon safely.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[AutoScaler] Encountered a fatal error during check:");
                e.printStackTrace();
            }
        }
    }

    /**
     * Called by the Load Balancer (Congestion) or HealthManager (Hardware Crash).
     * Protected by a Cooldown to prevent unnecessary calls.
     */
    public void triggerEmergencyWakeup(boolean isTrafficSpike) {
        synchronized (lock) {
            this.emergencyWakeupRequested = isTrafficSpike;
            this.lock.notifyAll();
        }
    }

    /**
     * Emergency Routine: Triggered by LB or HM when they detect a critical event
     * (traffic spike or hardware crash).
     */
    private void executeEmergencyRoutine() {

        // Reboot unhealthy nodes
        int nodesToRecover = clusterState.getAndResetDeadNodes();
        if (nodesToRecover > 0) {
            System.out.println("[AutoScaler] Hardware Crash Detected: Restoring " +
                               nodesToRecover + 
                               " nodes instantly.");
            this.capacityController.restoreCrashedNodes(nodesToRecover);
        }

        // Evaluates cluster load without CloudWatch metrics
        System.out.println("[AutoScaler] Evaluating Traffic Spikes...");
        this.desiredCapacity = this.scalingPolicy.calculateEmergencyTarget(
                this.desiredCapacity, 
                clusterState.getTotalDemand(),
                clusterState.getReadyNodeCount(),
                clusterState.getBootingNodeCount()
        );
        
        // This enforces the strictly controlled +2 limit for traffic scaling
        this.capacityController.fulfillDesiredCapacity(this.desiredCapacity);
    }

    /**
     * Default Routine: Regular checks to evaluate cluster health and load, 
     * triggered by the internal timer.
     */
    private void executeDefaultRoutine() {

        List<WorkerNode> readyNodes = clusterState.getNodesByStatus(WorkerNode.Status.READY);
        double clusterAverageCpu = this.calculateClusterAverageCpu(readyNodes);
        
        System.out.println("[AutoScaler] Executing Standard Math (CPU: " + clusterAverageCpu + "%)...");
        this.desiredCapacity = this.scalingPolicy.calculateDefaultTarget(
                this.desiredCapacity, 
                clusterAverageCpu, 
                clusterState.getTotalDemand(),
                clusterState.getReadyNodeCount(),
                clusterState.getBootingNodeCount()
        );
        
        this.capacityController.fulfillDesiredCapacity(this.desiredCapacity);
    }

    /**
     * Calculates the average CPU of the cluster.
     * Relies on the WorkerNode's internal state to skip machines which are warming up.
     * @param readyNodes The list of READY nodes in the cluster.
     * @return The average CPU across all READY healthy nodes, excluding those still in warm-up.
     */
    private double calculateClusterAverageCpu(List<WorkerNode> readyNodes) {
        double totalReadyCpu = 0.0;
        int validNodeCount = 0;

        for (WorkerNode node : readyNodes) {
            if (!node.isHealthy()) {
                System.out.println("[WARNING] Skipping CPU math for unhealthy node " 
                                    + node.getInstanceId());
                continue;
            }
            
            if (!node.isWarmedUp()) {
                System.out.println("[WARMUP] Node " 
                                    + node.getInstanceId() 
                                    + " is warming up. Treating its CPU as 0.0 for sum.");
                continue;
            } 

            double nodeCpu = awsClient.getAverageCpu(node.getInstanceId());

            if (nodeCpu >= 0.0) {
                totalReadyCpu += nodeCpu;
                validNodeCount++;
            } else {
                System.out.println("[WARNING] Skipping CPU math for node " 
                                    + node.getInstanceId() 
                                    + " due to CloudWatch metric read failure.");
            }
        }

        // Fetch the number of nodes currently booting
        long bootingNodeCount = clusterState.getBootingNodeCount();
        long validAndBootingCount = validNodeCount + bootingNodeCount;

        return (validAndBootingCount > 0) 
                ? (totalReadyCpu / validAndBootingCount) 
                : 0.0;
    }
}
