package cloud.cholewa.water;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WaterServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 47 connections of the managed database (20 allotted to the project, 23
    //with ristretto), counted twice during a rollout; a lost or mistyped key falls back to the library default without a
    //sound. The valve (HAS-316) adds two statements a minute and one a night - the share stays 2, the sum unchanged
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }

    //a context of a test must not read the Shelly Uni of the house (HAS-307)
    @Test
    void should_schedule_nothing_under_the_test_profile() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(applicationContext.getEnvironment().getProperty("shelly.sensor.uni.hot-water.port")).isEqualTo("1");
    }

    //the scheduler of cholewa-commons >= 1.11.0 (HAS-300, HAS-307)
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }

    //the water valve is read by the RPC client and the MQTT decoder of shelly-client (HAS-316); the Shelly Uni still
    //through WaterSensorClient
    @Test
    void should_have_the_clients_of_shelly_client() {
        assertThat(applicationContext.getBeanNamesForType(ShellyRpcClient.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyGen1Client.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyMqttDecoder.class)).hasSize(1);
    }

    //the events of every device on /device (HAS-316): the listener returns a Mono, so the ack is manual, and the trace
    //goes through the broker
    @Test
    void should_consume_the_device_events_with_manual_ack_and_observation() {
        final Environment environment = applicationContext.getEnvironment();
        assertThat(environment.getProperty("spring.rabbitmq.virtual-host")).isEqualTo("/device");
        assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("device");
        assertThat(environment.getProperty("spring.rabbitmq.listener.simple.acknowledge-mode")).isEqualTo("manual");
        assertThat(environment.getProperty("spring.rabbitmq.listener.simple.prefetch", Integer.class)).isEqualTo(3);
        assertThat(environment.getProperty("spring.rabbitmq.listener.simple.observation-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.rabbitmq.template.observation-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cholewa.required-properties[0].variable")).isEqualTo("rabbitmq-password");
    }

    //the alarms (HAS-319) go over a second connection, to /notification - one publisher, and no ConnectionFactory bean
    //that would make RabbitAutoConfiguration back off from the listener of the device events
    @Test
    void should_publish_the_alarms_over_a_connection_of_their_own() {
        final Environment environment = applicationContext.getEnvironment();
        assertThat(applicationContext.getBeanNamesForType(NotificationPublisher.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(org.springframework.amqp.rabbit.connection.ConnectionFactory.class))
            .hasSize(1);
        assertThat(environment.getProperty("notification.virtual-host")).isEqualTo("/notification");
        assertThat(environment.getProperty("notification.username")).isEqualTo("notification");
        assertThat(environment.getProperty("cholewa.required-properties[1].variable"))
            .isEqualTo("notification-rabbitmq-password");
    }
}
