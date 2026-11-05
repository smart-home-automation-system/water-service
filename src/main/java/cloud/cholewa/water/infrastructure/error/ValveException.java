package cloud.cholewa.water.infrastructure.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

//A request of the water valve the API refuses (HAS-316): the status says whose problem it is, the error names the cause,
//the message is answered as details
@Getter
public class ValveException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final WaterError error;

    private ValveException(final HttpStatus httpStatus, final WaterError error, final String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static ValveException notFound() {
        return new ValveException(HttpStatus.NOT_FOUND, WaterError.NOT_FOUND_VALVE, "No water valve is registered");
    }

    public static ValveException registryUnavailable() {
        return new ValveException(HttpStatus.SERVICE_UNAVAILABLE, WaterError.REGISTRY_UNAVAILABLE,
            "The water valve of the device registry is not known yet");
    }

    public static ValveException invalidRange(final String message) {
        return new ValveException(HttpStatus.BAD_REQUEST, WaterError.INVALID_RANGE, message);
    }

    public static ValveException invalidBill(final String message) {
        return new ValveException(HttpStatus.BAD_REQUEST, WaterError.INVALID_BILL, message);
    }

    public static ValveException notFoundBill(final long id) {
        return new ValveException(HttpStatus.NOT_FOUND, WaterError.NOT_FOUND_BILL, "No water bill " + id);
    }

    public static ValveException invalidImport(final String message) {
        return new ValveException(HttpStatus.BAD_REQUEST, WaterError.INVALID_IMPORT, message);
    }
}
