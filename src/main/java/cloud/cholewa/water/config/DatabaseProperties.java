package cloud.cholewa.water.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("database")
public record DatabaseProperties(String host, Integer port, String name, String username, String password) {
}
