package cloud.cholewa.water;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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

    //the pool is this service's share of the 22 connections of the managed database, counted twice
    //during a rollout; a lost or mistyped key falls back to the library default without a sound
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

    //shelly-client >= 2.0.0 brings clients of its own; this service reads the Shelly Uni through WaterSensorClient
    @Test
    void should_leave_the_clients_of_shelly_client_out() {
        assertThat(applicationContext.getBeanNamesForType(ShellyRpcClient.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(ShellyGen1Client.class)).isEmpty();
    }
}
