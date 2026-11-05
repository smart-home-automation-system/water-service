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

    //the valve refused the command or did not answer it; only its status, if it answered, goes past the first hop
    //(HAS-321)
    DEVICE_FAILED("Water valve failed");

    private final String description;
}
