package pt.ulisboa.tecnico.cnv.master.cloud;

import pt.ulisboa.tecnico.cnv.common.config.AwsConfig;
import pt.ulisboa.tecnico.cnv.master.config.AutoScalerConfig;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import pt.ulisboa.tecnico.cnv.master.healthdto.HealthResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AwsCloudClient {

    private final Ec2Client ec2Client;
    private final CloudWatchClient cloudWatchClient;

    public AwsCloudClient() {
        Region region = Region.of(AwsConfig.getRegion());
        
        // IAM Role handles authentication automatically
        this.ec2Client = Ec2Client.builder().region(region).build();
        this.cloudWatchClient = CloudWatchClient.builder().region(region).build();
    }

    /**
     * Launches a new worker instance using the defined Amazon Machine Image (AMI) 
     * and instance type in the launch template.
     * @return The ID of the newly launched instance.
     */
    public String launchWorker() {
        // Prints the launch template name for debugging purposes
        System.out.println("[DEBUG] AWS Client: Launching new worker from template " 
                            + AwsConfig.getLaunchTemplateName());

        // Tells AWS to use the specified launch template for booting the instance
        LaunchTemplateSpecification templateSpec = LaunchTemplateSpecification.builder()
                .launchTemplateName(AwsConfig.getLaunchTemplateName())
                .version("$Default")
                .build();

        // Requests AWS to start a new instance based on the launch template (exactly 1 instance)
        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .launchTemplate(templateSpec)
                .minCount(1)
                .maxCount(1)
                .build();

        // Get the instance ID of the newly launched worker for tracking and management
        RunInstancesResponse response = ec2Client.runInstances(runRequest);
        String newInstanceId = response.instances().get(0).instanceId();
        System.out.println("[DEBUG] AWS Client: Successfully booted instance " + newInstanceId);
        
        return newInstanceId;
    }

    /**
     * Terminates an existing worker instance.
     * @param instanceId The ID of the instance to terminate.
     */
    public void terminateWorker(String instanceId) {
        System.out.println("[DEBUG] AWS Client: Terminating worker " + instanceId);
        try {
            // Requests AWS to shut down the specified instance, which will stop it and release resources
            TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();
            ec2Client.terminateInstances(request);
        } catch (Ec2Exception e) {
            System.err.println("[TERMINATE] Failed to terminate worker " + 
                                instanceId + 
                                ": " + 
                                e.getMessage());
        }
    }


    /**
     * Retrieves the average CPU utilization for a given instance over a specified time window.
     * This method queries AWS CloudWatch for the "CPUUtilization" metric of the instance.
     * @param instanceId The ID of the instance to monitor.
     * @return The average CPU utilization as a percentage (0.0 to 100.0) of the most recent 
     *         datapoint registered. Returns 0.0 if no data is available.       
     */
    public double getAverageCpu(String instanceId) {
        
        int windowMinutes = AutoScalerConfig.getCloudWatchWindowMinutes();
        int periodSeconds = AutoScalerConfig.getCloudWatchPeriodSeconds();

        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(Duration.ofMinutes(windowMinutes));

        GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                .namespace("AWS/EC2")
                .metricName("CPUUtilization")
                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                .startTime(startTime)
                .endTime(endTime)
                .period(periodSeconds)
                .statistics(Statistic.AVERAGE)
                .build();

        try {
            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);
            List<Datapoint> datapoints = new ArrayList<>(response.datapoints());

            if (datapoints.isEmpty()) {
                return 0.0;
            }

            // Sorts the datapoints by timestamp descending
            datapoints.sort((d1, d2) -> d2.timestamp().compareTo(d1.timestamp()));
            return datapoints.get(0).average();

        } catch (CloudWatchException | SdkClientException e) {
            System.err.println("[CLOUDWATCH ERROR] Failed to fetch CPU for " 
                                + instanceId + ": " + e.getMessage());
            
            // Return -1.0 so the AutoScaler knows the read failed
            return -1.0; 
        }
    }

    /**
     * Checks the health status of a given instance by querying AWS EC2 for its instance status.
     * @param instanceId The ID of the instance to check.
     * @return True if the instance is healthy (status "ok"), false otherwise.
     */
    public HealthResult getInstanceHealthDetails(String instanceId) {
        try {
            DescribeInstancesResponse instances = ec2Client.describeInstances(
                DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build()
            );

            if (instances.reservations().isEmpty() || 
                instances.reservations().get(0).instances().isEmpty()) {
                System.out.println("[HEALTH] " + instanceId + " -> no instance info");
                return new HealthResult(false, "not-found");
            }

            Instance instance = instances.reservations().get(0).instances().get(0);
            String state = instance.state().nameAsString();

            if (!"running".equalsIgnoreCase(state)) {
                System.out.println("[HEALTH] " + instanceId + " -> state=" + state);
                return new HealthResult(false, state);
            }

            DescribeInstanceStatusResponse response = ec2Client.describeInstanceStatus(
                DescribeInstanceStatusRequest.builder()
                    .instanceIds(instanceId)
                    .includeAllInstances(true)
                    .build()
            );

            if (response.instanceStatuses().isEmpty()) {
                System.out.println("[HEALTH] " + instanceId + " -> status list empty");
                return new HealthResult(false, state);
            }

            InstanceStatus st = response.instanceStatuses().get(0);

            String systemStatus = st.systemStatus().statusAsString();

            String instanceStatus = st.instanceStatus().statusAsString();

            String ebsStatus =
                st.attachedEbsStatus() == null
                        ? "null"
                        : st.attachedEbsStatus().statusAsString();

            System.out.println(
                "[HEALTH] "
                + instanceId
                + " | state=" + state
                + " | system=" + systemStatus
                + " | instance=" + instanceStatus
                + " | ebs=" + ebsStatus
            );

            boolean systemOk = "ok".equalsIgnoreCase(st.systemStatus().statusAsString());
            boolean instanceOk = "ok".equalsIgnoreCase(st.instanceStatus().statusAsString());
            boolean ebsOk = st.attachedEbsStatus() == null ||
                        "ok".equalsIgnoreCase(st.attachedEbsStatus().statusAsString());

            return new HealthResult(systemOk && instanceOk && ebsOk, state);
        } catch (Ec2Exception e) {
            System.err.println("[HEALTH] Failed to check health for " + 
                                instanceId + 
                                ": " + 
                                e.awsErrorDetails().errorMessage());
            return new HealthResult(false, "error");
        }
    }
    
    /**
     * Retrieves the public IP address of a given instance.
     * @param instanceId The ID of the instance to query.
     * @return The public IP address as a string, or null if it doesn't exist yet.
     */
    public String getInstancePublicIp(String instanceId) {
        try {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();

            DescribeInstancesResponse response = ec2Client.describeInstances(request);

            for (Reservation reservation : response.reservations()) {
                for (Instance instance : reservation.instances()) {
                    if (instance.instanceId().equals(instanceId)) {
                        return instance.publicIpAddress();
                    }
                }
            }
        } catch (Ec2Exception e) {
            // The instance was just created but the read-database doesn't know about it yet
            if (e.awsErrorDetails() != null && 
                "InvalidInstanceID.NotFound".equals(e.awsErrorDetails().errorCode())) {
                System.out.println("[DEBUG] AWS Client: Instance " + 
                                    instanceId + 
                                    " not found in AWS database yet. Retrying...");
                return null;
            }
            
            // If it's a different AWS error, we still want to throw it
            System.err.println("[ERROR] AWS Client: Unexpected Ec2Exception: " + e.getMessage());
            throw e;
        }
        
        return null; // Return null if instance exists but IP is not assigned yet
    }

    /**
     * Waits for a given instance to have a public IP address assigned.
     * @param instanceId The ID of the instance to wait for.
     * @return The public IP address as a string.
     */
    public String waitForPublicIp(String instanceId) {
        int maxAttempts = AutoScalerConfig.getMaxPublicIpAttempts();
        int sleepSeconds = AutoScalerConfig.getSleepSecondsBetweenIpAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String ip = getInstancePublicIp(instanceId);

            if (ip != null && !ip.isBlank()) {
                System.out.println("[DEBUG] AWS Client: Instance " + instanceId 
                                                                   + " has public IP " + ip);
                return ip;
            }

            System.out.println("[DEBUG] AWS Client: Waiting for public IP on " + instanceId
                + " (attempt " + attempt + "/" + maxAttempts + ")");

            try {
                TimeUnit.SECONDS.sleep(sleepSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for public IP", e);
            }
        }
        throw new IllegalStateException("Timed out waiting for public IP for instance " + instanceId);
    }

    /**
     * Logs the current status of a given instance, including its state and health checks,
     * for debugging purposes.
     * @param instanceId The ID of the instance to log.
     */
    public void logInstanceStatus(String instanceId) {
        try {
            DescribeInstancesResponse instances = ec2Client.describeInstances(
                DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build()
            );

            if (instances.reservations().isEmpty() || instances.reservations().get(0).instances().isEmpty()) {
                System.out.println("[AWS DEBUG] instanceId=" + instanceId + " state=UNKNOWN (not found)");
                return;
            }

            Instance instance = instances.reservations().get(0).instances().get(0);
            String state = instance.state() == null ? "null" : instance.state().nameAsString();

            DescribeInstanceStatusResponse response = ec2Client.describeInstanceStatus(
                DescribeInstanceStatusRequest.builder()
                    .instanceIds(instanceId)
                    .includeAllInstances(true)
                    .build()
            );

            if (response.instanceStatuses().isEmpty()) {
                System.out.println(
                    "[AWS DEBUG] instanceId=" + instanceId +
                    " state=" + state +
                    " systemStatus=EMPTY" +
                    " instanceStatus=EMPTY" +
                    " ebsStatus=EMPTY"
                );
                return;
            }

            InstanceStatus st = response.instanceStatuses().get(0);

            String systemStatus = st.systemStatus() == null 
                    ? "null" 
                    : st.systemStatus().statusAsString();

            String instanceStatus = st.instanceStatus() == null 
                    ? "null" 
                    : st.instanceStatus().statusAsString();

            String ebsStatus = st.attachedEbsStatus() == null 
                    ? "null" 
                    : st.attachedEbsStatus().statusAsString();

            System.out.println(
                "[AWS DEBUG] instanceId=" + instanceId +
                " state=" + state +
                " systemStatus=" + systemStatus +
                " instanceStatus=" + instanceStatus +
                " ebsStatus=" + ebsStatus
            );

        } catch (Exception e) {
            System.err.println("[AWS DEBUG] Failed to log instance status for " + instanceId);
            e.printStackTrace();
        }
    }
}
