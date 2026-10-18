package pt.ulisboa.tecnico.cnv.master;

import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.master.autoscaler.AutoScalerDaemon;
import pt.ulisboa.tecnico.cnv.master.config.HealthManagerConfig;
import pt.ulisboa.tecnico.cnv.master.config.LoadBalancerConfig;
import pt.ulisboa.tecnico.cnv.master.healthmanager.HealthDaemon;
import pt.ulisboa.tecnico.cnv.master.loadbalancer.LoadBalancerProxy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MasterApplication {

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Master Node...");

        
        AutoScalerDaemon autoScaler = new AutoScalerDaemon();
        autoScaler.start();
        
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                new HealthDaemon(autoScaler),
                0,
                HealthManagerConfig.getCheckIntervalSeconds(),
                TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping autoscaler and health manager...");
            scheduler.shutdownNow();
        }));

        // Initialize the HTTP Server (Load Balancer) dynamically
        int port = LoadBalancerConfig.getPort();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        LoadBalancerProxy proxyHandler = new LoadBalancerProxy(autoScaler);

        // Map ONLY the explicitly supported workload paths to the Proxy
        for (String path : LoadBalancerConfig.getWorkloadPaths()) {
            server.createContext(path, proxyHandler);
            System.out.println("Mapped workload route: " + path);
        }

        // Map the Health Check path to a simple instantaneous response
        server.createContext(LoadBalancerConfig.getHealthCheckPath(), exchange -> {
            String msg = "Master Load Balancer is Healthy";
            byte[] bytes = msg.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        System.out.println("Mapped health check route: " + LoadBalancerConfig.getHealthCheckPath());

        // Start listening
        server.setExecutor(Executors.newFixedThreadPool(LoadBalancerConfig.getThreadPoolSize()));
        server.start();

        System.out.println("\nLoad Balancer successfully listening on port " + port);
    }
}