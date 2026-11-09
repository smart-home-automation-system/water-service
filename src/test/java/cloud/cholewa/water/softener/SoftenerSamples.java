package cloud.cholewa.water.softener;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;

//What the tests of the softener share (HAS-397): the clock of the house that moves, the properties, a JWT with its exp and
//the answer of the real softener (src/test/resources/iqua/detail-or-summary.json - the account and the address left out)
public final class SoftenerSamples {

    public static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    public static final Instant START = Instant.parse("2026-11-09T09:00:00Z");
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private SoftenerSamples() {
    }

    public static SoftenerProperties properties(final URI baseUrl) {
        return properties(baseUrl, null);
    }

    public static SoftenerProperties properties(final URI baseUrl, final String serialNumber) {
        return new SoftenerProperties(serialNumber,
            new SoftenerProperties.Cloud(baseUrl, "owner@example.com", "s3cr3t", Duration.ofMinutes(5)), Duration.ofMinutes(30),
            Duration.ofMinutes(5));
    }

    //a JWT whose payload carries the exp; the signature is not checked by the client
    public static String jwt(final Instant exp) {
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(("{\"sub\":\"user\",\"exp\":" + exp.getEpochSecond() + "}")
            .getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    public static String detail() {
        return resource("/iqua/detail-or-summary.json");
    }

    public static String resource(final String path) {
        try (InputStream stream = SoftenerSamples.class.getResourceAsStream(path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static IquaDevice device() {
        return JSON.convertValue(JSON.readTree(detail()).path("device"), IquaDevice.class);
    }

    public static final class MovableClock extends Clock {

        private Instant now = START;

        public void moveBy(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
