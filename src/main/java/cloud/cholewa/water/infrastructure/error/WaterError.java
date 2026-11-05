package cloud.cholewa.water.infrastructure.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request of the water valve the API refuses (HAS-316). Their names are the codes of the answer
//(ErrorId.codeOf) - wire contract for a caller that branches on them, the dashboard first of all, pinned by WaterErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum WaterError implements ErrorId {

    //the device registry has no water valve the service can read
    NOT_FOUND_VALVE("Water valve not found"),

    //the water valve of the registry is not known yet - database-service did not answer since the start
    REGISTRY_UNAVAILABLE("Device registry unavailable");

    private final String description;
}
