package pt.ulisboa.tecnico.cnv.master.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import pt.ulisboa.tecnico.cnv.master.config.LoadBalancerConfig;
import pt.ulisboa.tecnico.cnv.master.state.ClusterState;
import pt.ulisboa.tecnico.cnv.master.state.WorkerNode;
import pt.ulisboa.tecnico.cnv.master.state.PendingRequest;
import pt.ulisboa.tecnico.cnv.master.autoscaler.AutoScalerDaemon;

import java.io.IOException;

public class LoadBalancerProxy implements HttpHandler {

    private final ClusterState clusterState;
    private final SmartRouter smartRouter;
    private final RequestEstimator estimator;
    private final HttpDispatcher dispatcher;
    private final AutoScalerDaemon autoScaler;

    public LoadBalancerProxy(AutoScalerDaemon autoScaler) {
        this.clusterState = ClusterState.getInstance();
        this.smartRouter = SmartRouter.getInstance();
        this.estimator = new RequestEstimator();
        this.dispatcher = new HttpDispatcher();
        this.autoScaler = autoScaler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();

        // Estimate the cost of the request
        long estimatedCost = estimator.estimateComplexity(path, query);

        long maxAllowedLoad = LoadBalancerConfig.getMaxLoadPerNode();
        long lambdaMaxCost = LoadBalancerConfig.getLambdaMaxCost();

        // GATEKEEPER: Reject requests that physically exceed a node's maximum capacity
        if (estimatedCost > maxAllowedLoad) {
            System.out.println("[GATEKEEPER] Rejected request. Cost " + estimatedCost + 
                               " exceeds maximum capacity per node (" + maxAllowedLoad + ").");
            dispatcher.sendError(exchange, 413, "Request too complex: Exceeds maximum cluster capacity per node.");
            return;
        }
        
        // REGISTRATION AND GLOBAL ALARMS
        clusterState.addDemand(estimatedCost);
        
        // GLOBAL ALARM: Evaluate Auto-Scaler Trigger immediately after adding demand.
        // It's checked exactly once per request, before routing logic begins.
        if (smartRouter.needsScaleUp(maxAllowedLoad)) {
            System.out.println("[SCALE-UP] Demand exceeds future capacity. Waking up Auto-Scaler!");
            // wakes AS for the emmergency routine
            autoScaler.triggerEmergencyWakeup(true);
        }
        
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        int maxRetries = LoadBalancerConfig.getMaxRetryAttempts();

        // Outter try to always remove load of this request no matter what
        try {
            // ROUTING OR WAITING (The Retry Cycle)
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                WorkerNode targetNode = null;

                // Get centralized routing decision from the SmartRouter
                SmartRouter.RouteDecision decision = smartRouter.determineRoute(estimatedCost, maxAllowedLoad, lambdaMaxCost);

                // Execute the Action dictated by the SmartRouter
                switch (decision.action) {
                    
                    case SEND_TO_EC2:
                        targetNode = decision.node;
                        break;

                    case SEND_TO_LAMBDA:
                        System.out.println("[SPILLOVER] Active nodes full. Routing small request to Lambda. Attempt: " + attempt);
                        try {
                            dispatcher.forwardToLambda(exchange, requestBody);
                            return; // Success! Exit handler entirely
                        } catch (Exception e) {
                            System.out.println("[ERROR] Lambda invocation failed on attempt " + attempt + ". Retrying...");
                            continue; // Try again in the next loop iteration
                        }

                    case QUEUE:
                        System.out.println("[QUEUE] Enqueueing request (Cost: " + estimatedCost + "). Attempt: " + attempt);  
                        PendingRequest ticket = null;
                        try {
                            ticket = smartRouter.enqueueRequest(estimatedCost, attempt);
                            // Thread sleeps here securely without consuming CPU
                            targetNode = ticket.future.get();
                            
                        } catch (InterruptedException e) {
                            smartRouter.removeTicket(ticket);
                            Thread.currentThread().interrupt();
                            throw new IOException("Thread interrupted while waiting in queue.", e);
                        } catch (java.util.concurrent.ExecutionException e) {
                            smartRouter.removeTicket(ticket);
                            throw new IOException("Failed while waiting for queued request.", e);
                        }
                        break;
                }

                // EXECUTION AND CLEANUP
                try {
                    dispatcher.forwardToEc2(exchange, targetNode, requestBody);
                    return; // Successfully forwarded, exit the method

                } catch (Exception e) {
                    System.out.println("[ERROR] EC2 Node " + targetNode.getInstanceId() + 
                                       " failed on attempt " + attempt + ". Retrying...");
                    
                    targetNode.setHealthy(false);
                    
                } finally {
                    // Free the EC2 node space and wake up next in queue
                    smartRouter.freeNodeAndNotify(targetNode, estimatedCost, maxAllowedLoad);
                }
            }

            // Ran out of retries
            dispatcher.sendError(exchange, 502, "Cluster is experiencing severe instability. Please try again.");

        } finally {
            // Always clean up the global cluster demand no matter what
            clusterState.removeDemand(estimatedCost);

            // If demand dropped safely, wake the Auto-Scaler instantly
            if (smartRouter.needsScaleIn(maxAllowedLoad)) {
                System.out.println("[SCALE-IN] Demand dropped significantly. Waking up Auto-Scaler!");
                // Wakes AS but for the default routine
                autoScaler.triggerEmergencyWakeup(false);
            }
        }
    }
}