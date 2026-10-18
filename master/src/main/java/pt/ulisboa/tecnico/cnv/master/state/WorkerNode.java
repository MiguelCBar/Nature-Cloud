package pt.ulisboa.tecnico.cnv.master.state;

import java.util.concurrent.atomic.AtomicLong;

import pt.ulisboa.tecnico.cnv.master.config.AutoScalerConfig;

/**
 * Represents a single computing resource (EC2 instance or Lambda).
 * Completely thread-safe for concurrent load updates.
 */
public class WorkerNode {

    // Defines the lifecycle of the worker
    public enum Status {
        BOOTING,    // AWS is starting the instance, not ready for requests yet
        READY,      // Ready to receive requests from the Load Balancer
        DRAINING    // Auto-Scaler marked for termination, finishing current requests
    }

    private final String instanceId;
    private final String address;
    private final long launchTimeMs;

    // Tracks the exact time the node transitions to READY (Warm-Up period)
    private volatile long readyTimeMs = 0;

    private int consecutiveHealthCheckFailures = 0;
    
    // 'volatile' guarantees that if the Auto-Scaler changes the status, 
    // the Load Balancer threads see the change instantly in CPU cache.
    private volatile Status status;

    private volatile boolean isHealthy;
    
    // AtomicLong ensures that concurrent requests adding/removing load 
    // don't overwrite each other (avoids race conditions).
    private final AtomicLong currentEstimatedLoad;

    public WorkerNode(String instanceId, String address) {
        this.instanceId = instanceId;
        this.address = address;
        this.launchTimeMs = System.currentTimeMillis();
        this.status = Status.BOOTING; // Starts as booting by default
        this.currentEstimatedLoad = new AtomicLong(0);
        this.isHealthy = true;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getAddress() {
        return address;
    }

    public long getLaunchTimeMs() {
        return launchTimeMs;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        if (this.status == Status.BOOTING && status == Status.READY) {
            this.readyTimeMs = System.currentTimeMillis();
        }
        this.status = status;
    }

    // Called by the Load Balancer BEFORE forwarding a request
    public void addLoad(long complexity) {
        currentEstimatedLoad.addAndGet(complexity);
    }

    // Called by the Load Balancer AFTER a request finishes
    public void removeLoad(long complexity) {
        currentEstimatedLoad.addAndGet(-complexity);
    }

    public long getCurrentLoad() {
        return currentEstimatedLoad.get();
    }

    public int getConsecutiveHealthCheckFailures() {
        return consecutiveHealthCheckFailures;
    }

    public void incrementHealthCheckFailures() {
        consecutiveHealthCheckFailures++;
    }

    public void resetHealthCheckFailures() {
        consecutiveHealthCheckFailures = 0;
    }

    public void setHealthy(boolean healthy) {
        this.isHealthy = healthy;
    }

    public boolean isHealthy() {
        return isHealthy;
    }

    // Determines if the node has passed the warm-up period after becoming READY
    public boolean isWarmedUp() {
        if (this.status != Status.READY) {
            return false;
        }
        long warmupMs = AutoScalerConfig.getCloudWatchWarmupSeconds() * 1000L;
        return (System.currentTimeMillis() - this.readyTimeMs) >= warmupMs;
    }
}
