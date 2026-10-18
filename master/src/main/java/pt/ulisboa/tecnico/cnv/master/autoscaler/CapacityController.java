package pt.ulisboa.tecnico.cnv.master.autoscaler;

import pt.ulisboa.tecnico.cnv.master.cloud.AwsCloudClient;
import pt.ulisboa.tecnico.cnv.master.config.AutoScalerConfig;
import pt.ulisboa.tecnico.cnv.master.state.ClusterState;
import pt.ulisboa.tecnico.cnv.master.state.WorkerNode;

/**
 * The CapacityController is responsible for ensuring that the actual number of Worker nodes
 * in the cluster matches the desiredCapacity target determined by the ScalingPolicy. 
 * It handles both scaling up (booting new nodes) and scaling in (draining existing nodes) 
 * while respecting constraints such as maximum instances and booting concurrency limits. 
 * The controller also includes logic to attempt to rescue DRAINING nodes back to READY 
 * if we need to scale up, to avoid unnecessary booting of new nodes.
 */
public class CapacityController {

    private final AwsCloudClient awsClient;
    private final ClusterState clusterState;

    public CapacityController(AwsCloudClient awsClient, ClusterState clusterState) {
        this.awsClient = awsClient;
        this.clusterState = clusterState;
    }
    
    /**
     * Ensures that the actual number of Worker nodes matches the desiredCapacity target.
     * @param readyNodeCount The current number of READY nodes in the cluster.
     */
    void fulfillDesiredCapacity(int desiredCapacity) {
        int activeNodes = this.getCountByStatus(WorkerNode.Status.READY) 
                        + this.getCountByStatus(WorkerNode.Status.BOOTING);

        int maxBootsAllowed = AutoScalerConfig.getCriticalStepSize(); 
        int bootedThisCycle = 0;

        // Scale up; does not exceed maxBootsAllowed to avoid overwhelming
        while (activeNodes < desiredCapacity && bootedThisCycle < maxBootsAllowed) {
            System.out.println("[SCALING UP] Active (" + activeNodes + ") < Target (" + 
                                desiredCapacity + ").");
            
            // Try to change the status of a DRAINING node back to READY if we need to scale up
            // to avoid booting new nodes unnecessarily
            boolean reactivated = this.reactivateDrainingWorker();
            if (!reactivated) {
                System.out.println("No DRAINING nodes to reactivate. Booting new node.");
                this.bootNewWorker();
            }
            bootedThisCycle++;
            activeNodes++;
        }

        // Scale In
        while (activeNodes > desiredCapacity) {
            System.out.println("[SCALING IN] Active (" + activeNodes + ") > Target (" + 
                    desiredCapacity + "). Draining node.");
            boolean success = this.drainOneWorker();
            if (!success) break; 
            activeNodes--;
        }
    }

    /**
     * Instantly boots the exact number of replacement nodes..
     * This method intentionally ignores the getCriticalStepSize() limit.
     */
    public void restoreCrashedNodes(int deadNodeCount) {
        System.out.println("[CapacityController] URGENT: Bypassing traffic limits to restore " 
                           + deadNodeCount + " crashed node(s).");
        
        for (int i = 0; i < deadNodeCount; i++) {
            this.bootNewWorker(); 
        }
        
        System.out.println("[CapacityController] Hardware restoration initiated. " 
                           + deadNodeCount + " node(s) are now BOOTING.");
    }

    /**
     * Boots a new worker node by launching an EC2 instance and waiting for it to be ready.
     */
    void bootNewWorker() {
        String newId = awsClient.launchWorker();
        String ip = awsClient.waitForPublicIp(newId);
        WorkerNode newNode = new WorkerNode(newId, "http://" + ip + ":8000");
        
        // The constructor sets status to BOOTING by default
        clusterState.addNode(newNode); 

        System.out.println("[DEBUG] Added new worker node: WorkerNode{id='" + newNode.getInstanceId() 
                            + "', address='" + newNode.getAddress() 
                            + "', status=" + newNode.getStatus() 
                            + ", load=" + newNode.getCurrentLoad() + "}");
    }

    /**
     * Marks a single node for draining, preferring the node with the lowest current load.
     * @return true if successful, false if there are no READY nodes to drain.
     */
    private boolean drainOneWorker() {
        return clusterState.getAllNodes().stream()
                .filter(node -> node.getStatus() == WorkerNode.Status.READY)
                .min((n1, n2) -> Long.compare(n1.getCurrentLoad(), n2.getCurrentLoad()))
                .map(node -> {
                    System.out.println("[DRAIN] Draining node " + node.getInstanceId() 
                                        + " (Load: " + node.getCurrentLoad() + ").");
                    node.setStatus(WorkerNode.Status.DRAINING);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Attempts to reactivate a DRAINING node by flipping it back to READY.
     * Prefers the node with the lowest current load.
     * @return true if a node was reactivated, false if no DRAINING nodes exist.
     */
    private boolean reactivateDrainingWorker() {
        return clusterState.getAllNodes().stream()
                .filter(node -> node.getStatus() == WorkerNode.Status.DRAINING)
                .min((n1, n2) -> Long.compare(n1.getCurrentLoad(), n2.getCurrentLoad()))
                .map(node -> {
                    System.out.println("[REACTIVATE] Reactivating DRAINING node " 
                                        + node.getInstanceId() 
                                        + " back to READY.");
                    node.setStatus(WorkerNode.Status.READY);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Counts the number of nodes in a given status.
     * @param status The status to count (e.g., READY, BOOTING, DRAINING).
     * @return The count of nodes with the specified status.
     */
    int getCountByStatus(WorkerNode.Status status) {
        return (int) clusterState.getAllNodes().stream()
                .filter(node -> node.getStatus() == status)
                .count();
    }
}
