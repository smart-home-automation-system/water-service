package cloud.cholewa.water.infrastructure.error;

public class WaterException extends RuntimeException {
    public WaterException(final String message) {
        super(message);
    }
}
