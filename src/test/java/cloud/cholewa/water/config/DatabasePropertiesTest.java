package cloud.cholewa.water.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DatabasePropertiesTest {

    @Autowired
    private DatabaseProperties databaseProperties;

    @Autowired
    private Environment environment;

    @Test
    void should_bind_every_connection_property_from_the_database_prefix() {
        assertThat(databaseProperties.host()).isEqualTo("localhost");
        assertThat(databaseProperties.port()).isEqualTo(5432);
        assertThat(databaseProperties.name()).isEqualTo("dummyName");
        assertThat(databaseProperties.username()).isEqualTo("dummyUser");
        assertThat(databaseProperties.password()).isEqualTo("dummyPassword");
    }

    @Test
    void should_derive_the_flyway_url_from_the_connection_properties() {
        assertThat(environment.getProperty("spring.flyway.url"))
            .isEqualTo("jdbc:postgresql://localhost:5432/dummyName");
    }
}
