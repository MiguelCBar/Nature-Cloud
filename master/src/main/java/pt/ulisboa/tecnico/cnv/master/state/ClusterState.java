package pt.ulisboa.tecnico.cnv.master.state;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Global shared memory for the Load Balancer and Auto-Scaler.
 * Acts exclusively as a Registry for the current cluster nodes.
 * Uses a Singleton pattern.
 */
public class ClusterState {

    private static final ClusterState instance = new ClusterState();

    // ConcurrentHashMap allows the Auto-Scaler to add/remove nodes safely 
    // while the Load Balancer is simultaneously reading from it.
    // Key: InstanceId | Value: WorkerNode
    private final ConcurrentHashMap<String, WorkerNode> nodes = new ConcurrentHashMap<>();

    // Tracks nodes that died and require the Auto-Scaler to bypass normal traffic limits
    private final AtomicInteger pendingDeadNodes = new AtomicInteger(0);

    // Atomic counter representing the predicted load of the LB (Running + Queued)
    private final AtomicLong globalDemand = new AtomicLong(0);

    private ClusterState() {}

    public static ClusterState getInstance() {
        return instance;
    }

    /**
     * Returns the exact, real-time demand on the system.
     */
    public long getTotalDemand() {
        return globalDemand.get();
    }

    /**
     * Adds the estimated cost of a newly arrived request to the global demand.
     */
    public void addDemand(long cost) {
        globalDemand.addAndGet(cost);
    }

    /**
     * Removes the cost of a finished (or aborted/Lambda-redirected) request from the global demand.
     */
    public void removeDemand(long cost) {
        globalDemand.addAndGet(-cost);
    }

    /**
     * Get all nodes in the cluster state.
     * @return A collection of all WorkerNodes in the cluster state.
     */
    public Collection<WorkerNode> getAllNodes() {
        return nodes.values();
    }

    /**
     * Gets the count of nodes currently processing requests.
     */
    public long getReadyNodeCount() {
        return nodes.values().stream()
                .filter(n -> n.getStatus() == WorkerNode.Status.READY 
                        && n.isHealthy())
                .count();
    }

    /**
     * Gets the count of nodes currently booting up (requested by Auto-Scaler but not yet READY).
     */
    public long getBootingNodeCount() {
        return nodes.values().stream()
                .filter(n -> n.getStatus() == WorkerNode.Status.BOOTING 
                        && n.isHealthy())
                .count();
    }

    /**
     * Gets a list of nodes filtered by their status.
     * @param status The status to filter nodes by.
     * @return A list of WorkerNodes matching the specified status.
     */
    public List<WorkerNode> getNodesByStatus(WorkerNode.Status status) {
        return nodes.values().stream()
                .filter(n -> n.getStatus() == status)
                .collect(Collectors.toList());
    }

    /**
     * Add node to the cluster state. 
     * This should be called by the Auto-Scaler when a new node is launched and ready.
     * @param node The WorkerNode to add to the cluster state.
     */
    public void addNode(WorkerNode node) {
        nodes.put(node.getInstanceId(), node);
        System.out.println("[ClusterState] Added node: " + node.getInstanceId());
    }

    /**
     * Remove node from the cluster state.
     * This should be called by the Auto-Scaler when a node is terminated.
     * @param instanceId The instance ID of the node to remove.
     */
    public void removeNode(String instanceId) {
        nodes.remove(instanceId);
        System.out.println("[ClusterState] Removed node: " + instanceId);
    }

    /**
     * Called by the Health Manager to safely report dead nodes.
     */
    public void addDeadNodes(int count) {
        int total = this.pendingDeadNodes.addAndGet(count);
        System.out.println("[ClusterState] Registered " + count + " new dead node(s). Total pending recovery: " + total);
    }

    /**
     * Called by the Auto-Scaler to fetch the dead nodes count and instantly reset it to 0.
     * The getAndSet operation is strictly atomic.
     */
    public int getAndResetDeadNodes() {
        return this.pendingDeadNodes.getAndSet(0);
    }

}