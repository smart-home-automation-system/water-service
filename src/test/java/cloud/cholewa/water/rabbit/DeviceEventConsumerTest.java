package cloud.cholewa.water.rabbit;

import cloud.cholewa.water.valve.ValveEventRouter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//Whatever fails is logged and the message acknowledged (HAS-316): a Mono that errors is handed back by the broker at once
class DeviceEventConsumerTest {

    private static final String KEY = "neowatervalve-a1b2c3d4e5f6.status.number:201";

    private final ValveEventRouter router = mock(ValveEventRouter.class);
    private final DeviceEventConsumer sut = new DeviceEventConsumer(router);

    @Test
    void should_hand_the_routing_key_and_the_body_to_the_router() {
        final byte[] body = "{\"value\":1.0,\"last_update_ts\":1}".getBytes(StandardCharsets.UTF_8);
        when(router.route(KEY, body)).thenReturn(Mono.empty());

        sut.consume(message(body)).as(StepVerifier::create).verifyComplete();

        verify(router).route(KEY, body);
    }

    @Test
    void should_complete_whatever_the_router_fails_with() {
        final byte[] body = new byte[0];
        when(router.route(KEY, body)).thenReturn(Mono.error(new IllegalStateException("unexpected")));

        sut.consume(message(body)).as(StepVerifier::create).verifyComplete();
    }

    private static Message message(final byte[] body) {
        final MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(KEY);
        return new Message(body, properties);
    }
}
