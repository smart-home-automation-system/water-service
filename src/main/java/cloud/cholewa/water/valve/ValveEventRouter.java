package cloud.cholewa.water.valve;

import cloud.cholewa.shelly.codec.ShellyDecodeException;
import cloud.cholewa.shelly.codec.ShellyEvent;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.codec.ShellyTopic;
import cloud.cholewa.water.registry.ValveRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//Picks the messages of the water valve out of every device event of the queue (about 130 a minute, HAS-316): by the
//routing key alone, before a body is read - another device, or a component of the valve that is not one of its readings
//(sys, wifi, service:0), is left without a word, and so is everything while the registry has no valve. Only the five
//virtual components of ValveTracker and the online topic are decoded. A body that is not what its topic carries is a
//WARN, never an error out of the listener
@Slf4j
@Component
@RequiredArgsConstructor
public class ValveEventRouter {

    private final ValveRegistry valveRegistry;
    private final ShellyMqttDecoder shellyMqttDecoder;
    private final ValveTracker valveTracker;

    public Mono<Void> route(final String routingKey, final byte[] body) {
        return Mono.justOrEmpty(routingKey)
            .flatMap(key -> Mono.justOrEmpty(ShellyTopic.parse(key)))
            .filter(this::isOfTheValve)
            .filter(ValveEventRouter::isRead)
            .flatMap(topic -> decode(topic, body))
            .doOnNext(this::observe)
            .onErrorResume(ShellyDecodeException.class, failure -> Mono.fromRunnable(() ->
                log.warn("A message of the water valve could not be read: {}", failure.getMessage())))
            .then();
    }

    private boolean isOfTheValve(final ShellyTopic topic) {
        return valveRegistry.current()
            .filter(valve -> valve.deviceId().equalsIgnoreCase(topic.deviceId()))
            .isPresent();
    }

    private static boolean isRead(final ShellyTopic topic) {
        return topic.kind() == ShellyTopic.Kind.ONLINE
            || topic.kind() == ShellyTopic.Kind.STATUS && ValveTracker.isRead(topic.component(), topic.index());
    }

    private Mono<ShellyEvent> decode(final ShellyTopic topic, final byte[] body) {
        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty);
    }

    private void observe(final ShellyEvent event) {
        switch (event) {
            case ShellyEvent.Online online -> valveTracker.online(online.online());
            case ShellyEvent.Status status -> valveTracker.report(status.index(), status.status());
            case ShellyEvent.Notification ignored -> {
                //the valve has the RPC over MQTT off; nothing of it is read here
            }
        }
    }
}
