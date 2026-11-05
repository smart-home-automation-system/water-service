package cloud.cholewa.water.rabbit;

import cloud.cholewa.water.valve.ValveEventRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

//Every event of every device arrives here (HAS-316) - the queue is bound with "#", about 130 a minute: the status topics
//of the Shelly devices (routing key "<id>.status.<component>") and the DeviceEvent of the Eaton devices from amx-service.
//ValveEventRouter picks the water valve by the routing key. Logged at DEBUG only - at INFO the volume alone would drown
//the log
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceEventConsumer {

    private final ValveEventRouter valveEventRouter;

    @RabbitListener(queues = "${rabbitmq.queue.name}")
    Mono<Void> consume(final Message message) {
        final String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        return Mono.fromRunnable(() -> log.debug("Device event received with routing key: {}", routingKey))
            .then(Mono.defer(() -> valveEventRouter.route(routingKey, message.getBody())))
            //a Mono that errors is handed back by the broker at once - for a message that fails the same way a tight
            //loop - so whatever is left is logged and the message acknowledged
            .onErrorResume(throwable -> Mono.fromRunnable(() ->
                log.error("Error while consuming the device event {}", routingKey, throwable)))
            //the listener observation lives in a ThreadLocal, but the container subscribes in a way that does not
            //trigger the automatic capture, so the reactor context stays empty and traceId is lost on the first thread
            //switch; this captures it at subscribe, where it is still present
            .contextCapture();
    }
}
