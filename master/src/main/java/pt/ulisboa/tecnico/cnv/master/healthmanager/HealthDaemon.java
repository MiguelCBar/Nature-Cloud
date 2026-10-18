package pt.ulisboa.tecnico.cnv.master.healthmanager;

import pt.ulisboa.tecnico.cnv.master.autoscaler.AutoScalerDaemon;

public class HealthDaemon implements Runnable {

    private final HealthManager healthManager;

    public HealthDaemon(AutoScalerDaemon autoScaler) {
        this.healthManager = new HealthManager(autoScaler);
    }

    @Override
    public void run() {
        try {
            System.out.println("\n[HealthDaemon] Executing health check routine at " 
                                + System.currentTimeMillis());

            this.healthManager.startRoutine();

        } catch (Exception e) {
            System.err.println("[HealthDaemon] Encountered a fatal error during health check:");
            e.printStackTrace();
        }
    }
}