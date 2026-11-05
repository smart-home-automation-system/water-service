package cloud.cholewa.water.registry;

import cloud.cholewa.home.model.SmartDevice;

import java.util.Optional;

//The water valve of the registry (HAS-316): its id there, the id it reports under on the broker (the first part of its
//topics) and the address its RPC is read at
public record WaterValve(Long id, String deviceId, String address) {

    //the valve of a device of the registry, or nothing for one without the Shelly binding it is read by - the registry
    //refuses such a valve (database-service >= 0.21.0), so it is a mistake to log
    static Optional<WaterValve> of(final SmartDevice device) {
        return Optional.ofNullable(device.getShelly())
            .filter(shelly -> isGiven(shelly.getDeviceId()) && isGiven(shelly.getAddress()))
            .map(shelly -> new WaterValve(device.getId(), shelly.getDeviceId(), shelly.getAddress()));
    }

    private static boolean isGiven(final String text) {
        return text != null && !text.isBlank();
    }
}
