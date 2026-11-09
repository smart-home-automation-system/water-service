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

    public static ValveException invalidCommand(final String message) {
        return new ValveException(HttpStatus.BAD_REQUEST, WaterError.INVALID_COMMAND, message);
    }

    //status - what the Uni answered, null when it did not answer (HAS-404)
    public static ValveException pumpFailed(final Integer status) {
        return new ValveException(HttpStatus.BAD_GATEWAY, WaterError.DEVICE_FAILED, status == null
            ? "The circulation pump did not answer the command"
            : "The circulation pump refused the command with " + status);
    }

    //the Uni was not read before a start, or read without the temperature of the tank (HAS-404)
    public static ValveException pumpUnread() {
        return new ValveException(HttpStatus.BAD_GATEWAY, WaterError.DEVICE_FAILED,
            "The Shelly Uni of the hot water was not read, or without the temperature of the tank");
    }

    public static ValveException tankCold(final double tank, final double tankMin) {
        return new ValveException(HttpStatus.CONFLICT, WaterError.TANK_COLD,
            "The tank is at " + tank + " C, below " + tankMin + " C");
    }

    public static ValveException unknownMetric(final String metric) {
        return new ValveException(HttpStatus.BAD_REQUEST, WaterError.UNKNOWN_METRIC,
            "There is no metric '" + metric + "' of the softener");
    }

    public static ValveException softenerUnavailable() {
        return new ValveException(HttpStatus.SERVICE_UNAVAILABLE, WaterError.SOFTENER_UNAVAILABLE,
            "The water softener has not been read from the iQua cloud yet");
    }

    //the iQua cloud refused the command of the softener (status) or did not answer it (null) - HAS-400
    public static ValveException softenerCommandFailed(final Integer status) {
        return new ValveException(HttpStatus.BAD_GATEWAY, WaterError.SOFTENER_COMMAND_FAILED, status == null
            ? "The iQua cloud did not answer the command of the softener"
            : "The iQua cloud refused the command of the softener with " + status);
    }

    public static ValveException regenerationInProgress() {
        return new ValveException(HttpStatus.CONFLICT, WaterError.REGENERATION_IN_PROGRESS,
            "The softener regenerates now");
    }

    public static ValveException commandTooSoon(final long seconds) {
        return new ValveException(HttpStatus.CONFLICT, WaterError.COMMAND_TOO_SOON,
            "The last command of the softener was sent " + seconds + " s ago - one a minute at most");
    }

    public static ValveException invalidSoftenerCommand(final String when) {
        return new ValveException(HttpStatus.BAD_REQUEST, WaterError.INVALID_COMMAND,
            "when must be now or tonight, not '" + when + "'");
    }

    //the alerts of the softener not read from the iQua cloud (HAS-399) - what it answered goes to the log only
    public static ValveException softenerCloudFailed() {
        return new ValveException(HttpStatus.BAD_GATEWAY, WaterError.DEVICE_FAILED,
            "The iQua cloud did not answer the alerts of the softener");
    }

    //status - what the valve answered, null when it did not answer
    public static ValveException deviceFailed(final Integer status) {
        return new ValveException(HttpStatus.BAD_GATEWAY, WaterError.DEVICE_FAILED, status == null
            ? "The water valve did not answer the command"
            : "The water valve refused the command with " + status);
    }
}
