package cloud.cholewa.water.config;

import org.junit.jupiter.api.Test;
import org.zalando.logbook.BodyFilter;

import static org.assertj.core.api.Assertions.assertThat;

//What Logbook may not write of the iQua cloud (HAS-397): the account and the password of the login, the tokens of its
//answer and of a refresh, the account and the address that come with a device - the values of the softener stay
class LogbookConfigTest {

    private final BodyFilter filter = new LogbookConfig().maskingBodyFilter();

    @Test
    void should_mask_the_login() {
        assertThat(filter.filter("application/json", """
            {"email":"owner@example.com","password":"s3cr3t"}"""))
            .doesNotContain("owner@example.com").doesNotContain("s3cr3t");
    }

    @Test
    void should_mask_the_tokens_of_the_answer_and_of_a_refresh() {
        assertThat(filter.filter("application/json", """
            {"access_token":"a1b2c3","refresh_token":"d4e5f6","is_verified":true,"user_id":"u1"}"""))
            .doesNotContain("a1b2c3").doesNotContain("d4e5f6")
            .contains("\"is_verified\":true");
        assertThat(filter.filter("application/json", "{\"refresh_token\":\"d4e5f6\"}")).doesNotContain("d4e5f6");
    }

    @Test
    void should_mask_the_account_and_the_address_of_a_device_and_keep_its_values() {
        assertThat(filter.filter("application/json", """
            {"device":{"id":"dev-1","address":{"address1":"Main Street 1","address2":null,"city":"Town",
             "zip_postal_code":"00-001","country":"PL"},"user":{"email":"owner@example.com","first_name":"Alex",
             "last_name":"Smith","phone_number":"+48500000001"},
             "properties":{"salt_level_tenths":{"name":"salt_level_tenths","value":450}}}}"""))
            .doesNotContain("Main Street 1").doesNotContain("Town").doesNotContain("00-001")
            .doesNotContain("owner@example.com").doesNotContain("Alex").doesNotContain("Smith")
            .doesNotContain("+48500000001")
            .contains("\"value\":450");
    }
}
