package cloud.cholewa.water.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;

//The WebSocket client of the live stream of the softener (HAS-401) - Reactor Netty, wss by the scheme of the address; not
//the WebClient, so Logbook never logs the address with its token
@Configuration(proxyBeanMethods = false)
public class SoftenerLiveConfig {

    @Bean
    WebSocketClient iquaWebSocketClient() {
        return new ReactorNettyWebSocketClient();
    }
}
