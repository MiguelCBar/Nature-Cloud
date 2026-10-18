package pt.ulisboa.tecnico.cnv.master.loadbalancer;

import pt.ulisboa.tecnico.cnv.master.state.ClusterState;
import pt.ulisboa.tecnico.cnv.master.state.PendingRequest;
import pt.ulisboa.tecnico.cnv.master.state.WorkerNode;
import pt.ulisboa.tecnico.cnv.master.config.LoadBalancerConfig;

import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Engine that handles intelligent routing and waiting queues
 * Separates the routing logic and concurrency control from the pure Cluster State
 * Uses a Singleton pattern to maintain a single global queue for the Load Balancer
 */
public class SmartRouter {

    private static final SmartRouter instance = new SmartRouter();

    // Thread-safe queue sorted automatically by our retry attempts and strict FIFO rules
    private final PriorityBlockingQueue<PendingRequest> pendingQueue = new PriorityBlockingQueue<>();
    
    // Generates strict sequence IDs to maintain FIFO order globally among same-priority requests
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    private SmartRouter() {}

    public static SmartRouter getInstance() {
        return instance;
    }

    // --- ROUTING ACTION PATTERN ---
    /**
     * Enum defining the exact action the Proxy must take after consulting the Brain
     */
    public enum RouteAction {
        SEND_TO_EC2,
        SEND_TO_LAMBDA,
        QUEUE
    }

    /**
     * Data Transfer Object (DTO) conveying the routing decision safely to the Proxy
     */
    public static class RouteDecision {
        public final RouteAction action;
        public final WorkerNode node;

        public RouteDecision(RouteAction action, WorkerNode node) {
            this.action = action;
            this.node = node;
        }
    }


    // --- QUEUE MANAGEMENT ---
    /**
     * Utility to check the number of pending requests
     */
    public int getQueueSize() {
        return pendingQueue.size();
    }

    /**
     * Creates a ticket and places the request in the priority queue
     */
    public PendingRequest enqueueRequest(long estimatedCost, int attempt) {
        PendingRequest req = new PendingRequest(estimatedCost, attempt, sequenceGenerator.getAndIncrement());
        pendingQueue.add(req);
        return req;
    }

    /**
     * Safely removes a pending request from the queue
     * Used exclusively to clean up "Ghost Tickets" if a sleeping thread is interrupted/killed by the OS
     */
    public void removeTicket(PendingRequest ticket) {
        pendingQueue.remove(ticket);
    }


    // --- CORE ROUTING LOGIC ---
    /**
     * SPREADING STRATEGY (Least-Loaded):
     * Finds the node with the lowest current load that has enough space
     * Used during peacetime to optimize thermal and CPU performance
     */
    private Optional<WorkerNode> getLeastLoadedNode(long estimatedCost, long maxAllowedLoad) {
        return ClusterState.getInstance().getAllNodes().stream()
                .filter(node -> node.getStatus() == WorkerNode.Status.READY && node.isHealthy())
                .filter(node -> (node.getCurrentLoad() + estimatedCost) <= maxAllowedLoad)
                .min((node1, node2) -> Long.compare(node1.getCurrentLoad(), node2.getCurrentLoad()));
    }

    /**
     * PACKING STRATEGY (Best-Fit):
     * Finds the most loaded READY node that still has enough space
     * Used during congestion to tightly pack small requests and leave other nodes entirely free for XL requests
     */
    private Optional<WorkerNode> getBestFitNode(long estimatedCost, long maxAllowedLoad) {
        return ClusterState.getInstance().getAllNodes().stream()
                .filter(node -> node.getStatus() == WorkerNode.Status.READY && node.isHealthy())
                .filter(node -> (node.getCurrentLoad() + estimatedCost) <= maxAllowedLoad)
                .max((node1, node2) -> Long.compare(node1.getCurrentLoad(), node2.getCurrentLoad()));
    }

    /**
     * The "Supreme Brain". Decides the fate of a request atomically and is Thread-Safe
     * Implements Safe Backfilling and Dynamic Defragmentation
     */
    public synchronized RouteDecision determineRoute(long requestCost, long maxAllowedLoad, long lambdaMaxCost) {
        long currentCapacity = ClusterState.getInstance().getReadyNodeCount() * maxAllowedLoad;
        
        // totalDemand perfectly accounts for the queued requests AND the current requestCost
        long totalDemand = ClusterState.getInstance().getTotalDemand(); 

        // LAMBDA SPILLOVER TRIGGER
        // If the global demand broke the current capacity ceiling and request is small enough -> go to lambda
        if (totalDemand > currentCapacity && requestCost <= lambdaMaxCost) {
            return new RouteDecision(RouteAction.SEND_TO_LAMBDA, null);
        }

        // THE DYNAMIC FAST TRACK (Safe Backfilling)
        // If queue is congested -> Pack tight (Best-Fit) to defragment space for big waiting requests
        // If queue is empty -> Spread out (Least-Loaded) to keep load distributed
        boolean isCongested = !pendingQueue.isEmpty();
        Optional<WorkerNode> bestWorker = isCongested 
                ? getBestFitNode(requestCost, maxAllowedLoad)
                : getLeastLoadedNode(requestCost, maxAllowedLoad);

        if (bestWorker.isPresent()) {
            bestWorker.get().addLoad(requestCost); // Atomically reserve space so no concurrent thread steals it
            return new RouteDecision(RouteAction.SEND_TO_EC2, bestWorker.get());
        }

        // THE QUEUE (External Fragmentation / No Global Capacity)
        // if small, go to lambda. If big, queue up and wait for the next available slot on EC2
        if(requestCost > lambdaMaxCost) {
            return new RouteDecision(RouteAction.QUEUE, null);
        } else {
            return new RouteDecision(RouteAction.SEND_TO_LAMBDA, null);
        }
        
    }


    // --- NODE LIFECYCLE HANDLERS ---
    /**
     * Helper to sweep the queue and dispatch as many requests as possible to a specific node
     */
    private synchronized void dispatchQueueToNode(WorkerNode node, long maxAllowedLoad) {
        while (!pendingQueue.isEmpty()) {
            PendingRequest topRequest = pendingQueue.peek();
            
            if ((node.getCurrentLoad() + topRequest.estimatedCost) <= maxAllowedLoad) {
                pendingQueue.poll(); // Safely remove from queue
                node.addLoad(topRequest.estimatedCost); // Pre-reserve to prevent stealing
                topRequest.future.complete(node); // Surgically wake up the specific sleeping thread
            } else {
                // Top request does not fit. We MUST stop checking to maintain Strict Priority/FIFO order
                // This absolute block protects XL requests from starvation
                break;
            }
        }
    }

    /**
     * Removes load and immediately awakens queued requests if space allows
     */
    public synchronized void freeNodeAndNotify(WorkerNode node, long completedCost, long maxAllowedLoad) {
        node.removeLoad(completedCost);
        dispatchQueueToNode(node, maxAllowedLoad);
    }

    /**
     * Triggered by the HealthManager/AutoScaler when a Booting node finally becomes READY
     */
    public synchronized void onNodeReady(WorkerNode newNode, long maxAllowedLoad) {
        System.out.println("[SmartRouter] New Node " + newNode.getInstanceId() + " is READY! Sweeping queue...");
        dispatchQueueToNode(newNode, maxAllowedLoad);
    }

    /**
     * TRIGGER 2: THE AUTO-SCALER WAKEUP
     * Checks if the cluster needs more hardware, even accounting for nodes already booting
     */
    public boolean needsScaleUp(long maxAllowedLoadPerNode) {
        long readyNodes = ClusterState.getInstance().getReadyNodeCount();
        long bootingNodes = ClusterState.getInstance().getBootingNodeCount();

        if (bootingNodes >= 2) {
            return false;
        }
        
        long futureCapacity = (readyNodes + bootingNodes) * maxAllowedLoadPerNode;
        return ClusterState.getInstance().getTotalDemand() > futureCapacity;
    }

    /**
     * TRIGGER 3: THE SCALE-IN WAKEUP
     * Checks if the cluster is severely underutilized based on a strict mathematical safety margin
     * Prevents thrashing by ensuring the remaining nodes can comfortably handle the current demand
     */
    public boolean needsScaleIn(long maxLoadPerNode) {
        long readyNodes = ClusterState.getInstance().getReadyNodeCount();
        long bootingNodes = ClusterState.getInstance().getBootingNodeCount();

        // GOLDEN RULE 1: Never scale in if the cluster is currently expanding (nodes are booting)
        // Wait for the cluster to stabilize before taking destructive actions.
        if (bootingNodes > 0) {
            return false;
        }

        // GOLDEN RULE 2: Never try to scale in if we are at the minimum capacity 1 machine
        if (readyNodes <= 1) {
            return false;
        }

        long totalDemand = ClusterState.getInstance().getTotalDemand();
        long capacityAfterScaleIn = (readyNodes - 1) * maxLoadPerNode;
        
        // SAFETY MARGIN: Require a margin to actually consider scaling in
        long safetyMargin = LoadBalancerConfig.getScaleInSafetyMargin();
        return totalDemand <= (capacityAfterScaleIn - maxLoadPerNode * safetyMargin);
    }

}