package pt.ulisboa.tecnico.cnv.master.healthdto;

public class HealthResult {
    private final boolean isHealthy;
    private final String state;

    public HealthResult(boolean isHealthy, String state) {
        this.isHealthy = isHealthy;
        this.state = state;
    }

    public boolean getIsHealthy() {
        return this.isHealthy;
    }

    public String getState() {
        return this.state;
    }
}
