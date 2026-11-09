package cloud.cholewa.water.softener;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

//The client of the iQua cloud (HAS-397) against a stub: the login and the token held, a 401 answered by a refresh (a new
//login when the refresh fails), the token taken anew before its exp, the logins held for 30 min after three failed ones;
//a failure never quotes the body
class IquaClientTest {

    private static final Instant EXP = SoftenerSamples.START.plus(Duration.ofHours(24));

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private MockWebServer server;
    private IquaClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new IquaClient(WebClient.builder(), SoftenerSamples.JSON,
            SoftenerSamples.properties(URI.create("http://localhost:" + server.getPort() + "/v1")), clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build();
    }

    private static MockResponse status(final int code) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
            .body("{\"title\":\"Unauthorized\",\"detail\":\"token s3cr3t-token is expired\"}").build();
    }

    private static MockResponse token(final String access, final String refresh) {
        return json("""
            {"access_token":"%s","refresh_token":"%s","is_verified":true,"user_id":"u1"}""".formatted(access, refresh));
    }

    private static MockResponse devices() {
        return json("""
            {"data":[{"id":"dev-1","serial_number":"SN1","nickname":"Softener","system_type":"demand_softener",
              "thing_name":"thing-1","is_online":true}],"page":1,"per_page":20,"total":1}""");
    }

    private static String first() {
        return SoftenerSamples.jwt(EXP);
    }

    @Test
    void should_log_in_and_list_the_devices_with_the_token() throws InterruptedException {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(devices());

        client.devices().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).containsExactly(
                new IquaDevice("dev-1", "SN1", "Softener", "demand_softener", "thing-1", true, null, null)))
            .verifyComplete();

        final RecordedRequest login = server.takeRequest();
        assertThat(login.getMethod()).isEqualTo("POST");
        assertThat(login.getUrl().encodedPath()).isEqualTo("/v1/auth/login");
        assertThat(SoftenerSamples.JSON.readTree(login.getBody().utf8())).isEqualTo(SoftenerSamples.JSON.readTree("""
            {"email":"owner@example.com","password":"s3cr3t"}"""));
        final RecordedRequest list = server.takeRequest();
        assertThat(list.getMethod()).isEqualTo("GET");
        assertThat(list.getUrl().encodedPath()).isEqualTo("/v1/devices");
        assertThat(list.getHeaders().get("Authorization")).isEqualTo("Bearer " + first());
    }

    @Test
    void should_keep_the_token_for_the_next_call() throws InterruptedException {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(devices());
        server.enqueue(devices());

        client.devices().then(client.devices()).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(3);
        server.takeRequest();
        server.takeRequest();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/v1/devices");
    }

    @Test
    void should_read_the_device_of_the_detail() throws InterruptedException {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(json("""
            {"device":{"id":"dev-1","serial_number":"SN1","is_online":false,"properties":{
              "salt_level_tenths":{"name":"salt_level_tenths","value":450,"created_at":"2026-01-01T00:00:00Z",
                "updated_at":"2026-11-09T08:59:00Z"}}}}"""));

        client.detail("dev-1").as(StepVerifier::create)
            .assertNext(device -> {
                assertThat(device.id()).isEqualTo("dev-1");
                assertThat(device.online()).isFalse();
                assertThat(device.properties()).containsOnlyKeys("salt_level_tenths");
                assertThat(device.properties().get("salt_level_tenths").value().asInt()).isEqualTo(450);
            })
            .verifyComplete();

        server.takeRequest();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/v1/devices/dev-1/detail-or-summary");
    }

    //the summary alone comes when the account no longer has full access to the device
    @Test
    void should_fail_on_a_summary_alone() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(json("{\"summary\":{\"id\":\"dev-1\",\"thing_name\":\"t\",\"system_type\":\"s\"}}"));

        client.detail("dev-1").as(StepVerifier::create)
            .expectErrorMessage("The iQua cloud gave no detail of the softener")
            .verify();
    }

    @Test
    void should_refresh_the_token_on_a_401_and_call_once_more() throws InterruptedException {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(status(401));
        server.enqueue(token("second", "refresh-2"));
        server.enqueue(devices());

        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        server.takeRequest();
        server.takeRequest();
        final RecordedRequest refresh = server.takeRequest();
        assertThat(refresh.getUrl().encodedPath()).isEqualTo("/v1/auth/refresh");
        assertThat(SoftenerSamples.JSON.readTree(refresh.getBody().utf8()))
            .isEqualTo(SoftenerSamples.JSON.readTree("{\"refresh_token\":\"refresh-1\"}"));
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("Bearer second");
    }

    @Test
    void should_log_in_anew_when_the_refresh_fails() throws InterruptedException {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(status(401));
        server.enqueue(status(401));
        server.enqueue(token("third", "refresh-3"));
        server.enqueue(devices());

        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        server.takeRequest();
        server.takeRequest();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/v1/auth/refresh");
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/v1/auth/login");
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("Bearer third");
    }

    //one more call after a new token, never a loop
    @Test
    void should_fail_when_the_new_token_is_refused_too() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(status(401));
        server.enqueue(token("second", "refresh-2"));
        server.enqueue(status(401));

        client.devices().as(StepVerifier::create)
            .expectErrorMessage("The iQua cloud refused a new token for the devices")
            .verify();

        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    @Test
    void should_refresh_the_token_an_hour_before_its_exp() throws InterruptedException {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(devices());
        server.enqueue(token("second", "refresh-2"));
        server.enqueue(devices());

        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofHours(23));
        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        server.takeRequest();
        server.takeRequest();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/v1/auth/refresh");
    }

    @Test
    void should_take_the_exp_of_the_jwt_and_twelve_hours_without_one() {
        assertThat(client.expiryOf(first())).isEqualTo(EXP);
        assertThat(client.expiryOf("not-a-jwt")).isEqualTo(SoftenerSamples.START.plus(Duration.ofHours(12)));
        assertThat(client.expiryOf("a.bm90LWpzb24.c")).isEqualTo(SoftenerSamples.START.plus(Duration.ofHours(12)));
    }

    //a wrong password: three logins, then none for 30 min - the cloud would block the account
    @Test
    void should_hold_the_logins_after_three_failed_ones() {
        server.enqueue(status(401));
        server.enqueue(status(401));
        server.enqueue(status(401));

        for (int attempt = 0; attempt < 3; attempt++) {
            client.devices().as(StepVerifier::create)
                .expectErrorMessage("The iQua cloud answered HTTP 401 for a login")
                .verify();
        }
        client.devices().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure.getMessage())
                .startsWith("Logins to the iQua cloud are held back: until"))
            .verify();
        assertThat(server.getRequestCount()).isEqualTo(3);

        clock.moveBy(Duration.ofMinutes(30));
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(devices());
        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(5);
    }

    //the body may quote the token back - never in the message, which the JSON logs store
    @Test
    void should_fail_with_the_status_alone() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(status(503));

        client.devices().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(IquaException.class)
                .hasMessage("The iQua cloud answered HTTP 503 for the devices"))
            .verify();
    }

    @Test
    void should_fail_on_an_answer_that_is_not_json() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(new MockResponse.Builder().body("<html>s3cr3t</html>").build());

        client.devices().as(StepVerifier::create)
            .expectErrorMessage("The iQua cloud answered something that is not JSON for the devices")
            .verify();
    }

    @Test
    void should_fail_on_a_login_answer_without_a_token() {
        server.enqueue(json("{\"is_verified\":false,\"user_id\":\"u1\"}"));

        client.devices().as(StepVerifier::create)
            .expectErrorMessage("The iQua cloud gave no token for a login")
            .verify();
    }

    //{} is not the model: a failed call, never an account without devices or a softener of nulls
    @Test
    void should_fail_on_answers_that_are_not_the_model() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(json("{}"));
        server.enqueue(json("{\"device\":{}}"));

        client.devices().as(StepVerifier::create)
            .expectErrorMessage("The iQua cloud answered no list of the devices")
            .verify();
        client.detail("dev-1").as(StepVerifier::create)
            .expectErrorMessage("The iQua cloud answered a softener without its properties")
            .verify();
    }

    //the message of Jackson would quote the answer into the log
    @Test
    void should_fail_with_a_fixed_message_on_a_device_that_does_not_convert() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(json("{\"device\":{\"id\":\"dev-1\",\"properties\":[\"s3cr3t-value\"]}}"));

        client.detail("dev-1").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(IquaException.class)
                .hasMessage("The iQua cloud answered the softener not as their model"))
            .verify();
    }

    @Test
    void should_carry_the_status_of_a_refused_call() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(status(404));

        client.detail("dev-1").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((IquaException) failure).getStatus()).isEqualTo(404))
            .verify();
    }

    //a token of an hour would be renewed at once with a fixed margin of an hour: a quarter of its life is left instead
    @Test
    void should_renew_a_short_token_with_a_quarter_of_its_life_left() {
        final String hour = SoftenerSamples.jwt(SoftenerSamples.START.plus(Duration.ofHours(1)));
        server.enqueue(token(hour, "refresh-1"));
        server.enqueue(devices());
        server.enqueue(devices());
        server.enqueue(token("second", "refresh-2"));
        server.enqueue(devices());

        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofMinutes(44));
        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofMinutes(1));
        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(5);
    }

    //a refresh token the cloud refused is dropped: the next token is a login, not that refresh again
    @Test
    void should_drop_a_refused_refresh_token() throws InterruptedException {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(status(401));
        server.enqueue(status(401));
        server.enqueue(json("{\"access_token\":\"second\",\"is_verified\":true,\"user_id\":\"u1\"}"));
        server.enqueue(devices());
        server.enqueue(status(401));
        server.enqueue(token("third", "refresh-3"));
        server.enqueue(devices());

        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        client.devices().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        for (int request = 0; request < 6; request++) {
            server.takeRequest();
        }
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/v1/auth/login");
    }

    @Test
    void should_fail_when_the_cloud_does_not_answer_in_time() {
        server.enqueue(token(first(), "refresh-1"));
        server.enqueue(new MockResponse.Builder().headersDelay(15, TimeUnit.SECONDS).body("{}").build());

        client.devices().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure.getMessage())
                .startsWith("The iQua cloud did not answer for the devices: "))
            .verify(Duration.ofSeconds(20));
    }
}
