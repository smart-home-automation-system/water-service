package cloud.cholewa.water.circulation;

import cloud.cholewa.shelly.model.ExternalTemperatureValue;
import cloud.cholewa.shelly.model.Relay;
import cloud.cholewa.shelly.model.ShellyUniStatusResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

//One read of the Shelly Uni of the hot water (HAS-404): the relay of the pump and the two sensors, at the time of the read
//(house time). What the Uni did not send is null - never off, never 0
//pumpOn - relays[1] (the relay CIRCULATION), null when the answer has no such relay or no state
//tank - ext_temperature "0", the hot water of the tank
//returnTemperature - ext_temperature "1", the sensor of the circulation (whether it sits on the return is the question of
//  stopOnReturn)
record UniReading(Boolean pumpOn, Double tank, Double returnTemperature, LocalDateTime readAt) {

    static final int PUMP_RELAY = 1;

    static UniReading of(final ShellyUniStatusResponse status, final LocalDateTime readAt) {
        return new UniReading(pumpOf(status.getRelays()), temperatureOf(status.getExtTemperature(), "0"),
            temperatureOf(status.getExtTemperature(), "1"), readAt);
    }

    private static Boolean pumpOf(final List<Relay> relays) {
        return Optional.ofNullable(relays)
            .filter(list -> list.size() > PUMP_RELAY)
            .map(list -> list.get(PUMP_RELAY))
            .map(Relay::getIson)
            .orElse(null);
    }

    private static Double temperatureOf(final Map<String, ExternalTemperatureValue> sensors, final String index) {
        return Optional.ofNullable(sensors)
            .map(map -> map.get(index))
            .map(ExternalTemperatureValue::gettC)
            .filter(Double::isFinite)
            .orElse(null);
    }
}
