package pt.ulisboa.tecnico.cnv.master.healthmanager;

import pt.ulisboa.tecnico.cnv.master.cloud.AwsCloudClient;
import pt.ulisboa.tecnico.cnv.master.autoscaler.AutoScalerDaemon;
import pt.ulisboa.tecnico.cnv.master.config.AutoScalerConfig;
import pt.ulisboa.tecnico.cnv.master.state.ClusterState;
import pt.ulisboa.tecnico.cnv.master.state.WorkerNode;

import pt.ulisboa.tecnico.cnv.master.healthdto.HealthResult;

import pt.ulisboa.tecnico.cnv.master.loadbalancer.SmartRouter;
import pt.ulisboa.tecnico.cnv.master.config.LoadBalancerConfig;

import java.util.ArrayList;
import java.util.List;


/**
 * The HealthManager evaluates the Lifecyle and Health of each Worker node in the cluster.
 * Responsibilities:
 * 1. Changes the status of a BOOTING node to READY, for the Load Balancer to immediately use it,
 * instead of waiting for the Auto Scaler slow routine. 
 * 2. Detects unhealthy nodes and terminates them in the AWS and removes them from the ClusterState.
 * 3. Detects when a DRAINING node has finished draining and terminates it to free up resources.
 * 4. DOES NOT boot new nodes to replace unhealthy ones. It leaves capacity management to the Auto
 * Scaler.
 */
public class HealthManager {

    private final AwsCloudClient awsClient;
    private final ClusterState clusterState;
    private final AutoScalerDaemon autoScaler;

    public HealthManager(AutoScalerDaemon autoScaler) {
        this.awsClient = new AwsCloudClient();
        this.clusterState = ClusterState.getInstance();
        this.autoScaler = autoScaler;
    }

    /**
     * Evaluates the entire cluster and terminates any nodes 
     * that fail their health checks.
     */
    public void startRoutine() {
        List<WorkerNode> nodesToKill = new ArrayList<>();
        int nodesCrashCount = 0;

        for (WorkerNode node : clusterState.getAllNodes()) {
            if (this.processWorkerHealth(node)) {
                nodesToKill.add(node);
                if (node.getStatus() == WorkerNode.Status.READY || 
                    node.getStatus() == WorkerNode.Status.BOOTING) {
                    nodesCrashCount++;
                }
            }
        }

        for (WorkerNode deadNode : nodesToKill) {
            clusterState.removeNode(deadNode.getInstanceId());
            awsClient.terminateWorker(deadNode.getInstanceId());
            System.out.println("[HealthManager] Terminated node " 
                                + deadNode.getInstanceId() 
                                + " due to health check failures.");
        }

        if (nodesCrashCount > 0) {
            clusterState.addDeadNodes(nodesCrashCount);
            System.out.println("[HealthManager] CRITICAL: " + 
                                nodesCrashCount + 
                                " unexpected crashes detected." + 
                                " Waking Auto-Scaler for Disaster Recovery!");
            autoScaler.triggerEmergencyWakeup(true);
        }
    }

    /**
     * Evaluates the health of a single node based on its lifecycle status.
     * @param node The WorkerNode to evaluate.
     * @return true if the node should be terminated, false otherwise.
     */
    private boolean processWorkerHealth(WorkerNode node) {
        String id = node.getInstanceId();
        long ageMs = System.currentTimeMillis() - node.getLaunchTimeMs();
        long graceMs = AutoScalerConfig.getBootGracePeriodSeconds() * 1000L;

        HealthResult healthResult = awsClient.getInstanceHealthDetails(id);
        boolean healthy = healthResult.getIsHealthy();
        node.setHealthy(healthy);

        if (healthy) {
            node.resetHealthCheckFailures();
        } else {
            String state = healthResult.getState();
            // If AWS reports the instances are neither BOOTING, READY nor DRAINING
            if (!("pending".equalsIgnoreCase(state) || "running".equalsIgnoreCase(state))) {
                System.out.println("[WARNING] Node " 
                                   + id 
                                   + " is in fatal state: " 
                                   + state);
                return true;
            }

            // If it is still booting and within grace period, we do nothing
            if (node.getStatus() == WorkerNode.Status.BOOTING && ageMs < graceMs) {
                System.out.println("Node " + id + " is still booting (Age: " + (ageMs/1000) + "s).");
            } 
            // If it's not a fatal state, and either (past grace) or (not booting), increment
            else {
                node.incrementHealthCheckFailures();
                System.out.println("[WARNING] Node " 
                                   + id 
                                   + " failed health check. Consecutive failures: " 
                                   + node.getConsecutiveHealthCheckFailures());
            }
        }

        switch (node.getStatus()) {
            case BOOTING:
                if (healthy) {
                    System.out.println("Node " + id + " finished booting. READY for use.");
                    node.setStatus(WorkerNode.Status.READY);

                    // Instantly awake the Load Balancer queue to use this empty node
                    SmartRouter.getInstance().onNodeReady(node, LoadBalancerConfig.getMaxLoadPerNode());
                    break;

                } else if (ageMs >= graceMs && node.getConsecutiveHealthCheckFailures() >= 
                                                AutoScalerConfig.getMaxHealthCheckTries()) {
                    System.out.println("Node " + id + " failed repeated health checks during boot. Terminating.");
                    return true;
                } 
                break;

            case READY:
                if (!healthy && node.getConsecutiveHealthCheckFailures() >= 
                                                AutoScalerConfig.getMaxHealthCheckTries()) {
                    System.out.println("READY node " + id + " repeatedly unhealthy. Terminating.");
                    return true;
                }
                break;

            case DRAINING:
                if (!healthy) {
                    System.out.println("Node " + id + " crashed while draining. Terminating instantly.");
                    return true;
                }
                long currentLoad = node.getCurrentLoad();
                if (currentLoad <= 0) {
                    System.out.println("Node " + id + " finished draining. Terminating.");
                    return true;
                } 
                System.out.println("Node " + id + " is draining... Load remaining: " + currentLoad);
                break;
        }

        return false;
    }
}
