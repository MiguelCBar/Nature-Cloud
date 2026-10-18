package pt.ulisboa.tecnico.cnv.master.state;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a pending request waiting for an EC2 instance to become available.
 * Implements Comparable to enforce VIP Retries and strict FIFO order.
 */
public class PendingRequest implements Comparable<PendingRequest> {
    public final long estimatedCost;
    public final int attempt;
    public final long sequenceId;
    public final CompletableFuture<WorkerNode> future;

    public PendingRequest(long estimatedCost, int attempt, long sequenceId) {
        this.estimatedCost = estimatedCost;
        // attempt > 0 means it's a retry (VIP priority)
        this.attempt = attempt; 
        // sequenceId ensures strict FIFO for requests with the same attempt level
        this.sequenceId = sequenceId; 
        this.future = new CompletableFuture<>();
    }

    @Override
    public int compareTo(PendingRequest other) {
        // VIP Priority: higher attempts (retries from dead nodes) get served first
        if (this.attempt != other.attempt) {
            return Integer.compare(other.attempt, this.attempt); // descending order
        }
        // FIFO Priority: if attempts are equal, oldest request gets served first
        return Long.compare(this.sequenceId, other.sequenceId); // ascending order
    }
}