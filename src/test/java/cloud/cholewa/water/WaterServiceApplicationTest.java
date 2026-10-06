package cloud.cholewa.water;

import cloud.cholewa.commons.database.DatabaseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WaterServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 22 connections of the managed database, counted twice
    //during a rollout; a lost or mistyped key falls back to the library default without a sound
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }
}
