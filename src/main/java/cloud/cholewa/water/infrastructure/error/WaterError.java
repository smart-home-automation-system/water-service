package cloud.cholewa.water.infrastructure.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request of the water valve or its consumption the API refuses (HAS-316, HAS-317). Their names are the codes of the answer
//(ErrorId.codeOf) - wire contract for a caller that branches on them, the dashboard first of all, pinned by WaterErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum WaterError implements ErrorId {

    //the device registry has no water valve the service can read
    NOT_FOUND_VALVE("Water valve not found"),

    //the water valve of the registry is not known yet - database-service did not answer since the start
    REGISTRY_UNAVAILABLE("Device registry unavailable"),

    //a range of the consumption that is not day, week, month or year, or a date that is not YYYY-MM-DD (HAS-317)
    INVALID_RANGE("Invalid consumption range"),

    //a bill the API refuses - its reason in details: its period, a volume or an amount, an overlap with another (HAS-318)
    INVALID_BILL("Invalid water bill"),

    //no bill of that id (HAS-318)
    NOT_FOUND_BILL("Water bill not found"),

    //an import of the daily consumption the API refuses - its reason in details (HAS-318)
    INVALID_IMPORT("Invalid consumption import"),

    //a command of the valve the API refuses - a state that is not open or close, or a source that is not a short word
    //(HAS-321)
    INVALID_COMMAND("Invalid valve command"),

    //the valve or the circulation pump refused the command or did not answer it; only its status, if it answered, goes
    //past the first hop (HAS-321, HAS-404)
    DEVICE_FAILED("Device failed"),

    //a start of the circulation refused: the tank is below water.circulation.tank-min - the pump would push cold water
    //round (HAS-404)
    TANK_COLD("Hot water tank too cold"),

    //the softener has not been read from the iQua cloud since the start (HAS-397)
    SOFTENER_UNAVAILABLE("Water softener unavailable"),

    //a metric of the history of the softener that is not one of SoftenerMetric (HAS-398)
    UNKNOWN_METRIC("Unknown metric"),

    //the iQua cloud refused the command of the softener or did not answer it; only its status, if it answered, goes past
    //the first hop (HAS-400)
    SOFTENER_COMMAND_FAILED("Softener command failed"),

    //a command of the regeneration refused while the softener regenerates (HAS-400)
    REGENERATION_IN_PROGRESS("Regeneration in progress"),

    //a command of the softener within a minute of the last one - one a minute at most (HAS-400)
    COMMAND_TOO_SOON("Command too soon"),

    //the iQua cloud refused the live stream of the softener for its throttle (429) - never retried (HAS-401)
    LIVE_THROTTLED("Live stream throttled");

    private final String description;
}
