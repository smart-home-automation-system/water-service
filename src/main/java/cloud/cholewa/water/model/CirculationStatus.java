package cloud.cholewa.water.model;

public record CirculationStatus(
    double temperature,
    boolean pumpActive
) {
}
