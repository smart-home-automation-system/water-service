package cloud.cholewa.water.softener;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.StreamSupport;

//The cloud of the iQua application (HAS-397), https://api.myiquaapp.com/v1 - its public OpenAPI at /v1/openapi.json.
//- The login: POST /auth/login {email, password} answers access_token (a JWT of about 24 h) and refresh_token; sent as
//  "Authorization: Bearer". Both are held in memory only. The token is taken anew an hour before the exp of its JWT - with a
//  quarter of its life left for a shorter one; 12 h after it was taken when the JWT has none - and once when a call
//  answers 401: POST /auth/refresh {refresh_token} first, a new login when the refresh fails (a refused refresh token is
//  dropped).
//- After LoginGuard.FAILURES failed logins within 30 min no login is tried for 30 min - the cloud blocks an account above
//  about 1000 calls a day; nothing is retried in a loop, only the one call after a new token.
//- A failure is an IquaException with the HTTP status at most, never the body.
//- Built from the injected builder: the calls are traced and logged by Logbook, whose filters mask the password, the
//  tokens and the account (LogbookConfig); the Authorization header is masked by Logbook itself. 5 s to connect, 10 s for
//  an answer, a new connection for every call (one call in five minutes - a pooled connection would be closed in between)
@Slf4j
@Component
public class IquaClient {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;
    static final Duration REFRESH_BEFORE = Duration.ofHours(1);
    //a token is taken anew with this share of its life left at the latest - for a token that lives four hours or less
    static final int REFRESH_SHARE = 4;
    static final Duration LIFETIME_WITHOUT_EXP = Duration.ofHours(12);
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";

    private final WebClient webClient;
    private final JsonMapper jsonMapper;
    private final SoftenerProperties.Cloud cloud;
    private final Clock clock;
    private final LoginGuard loginGuard;
    private final AtomicReference<Token> token = new AtomicReference<>();
    //the token being taken, shared by the calls that need one meanwhile
    private final AtomicReference<Mono<String>> taking = new AtomicReference<>();

    public IquaClient(
        final WebClient.Builder webClientBuilder,
        final JsonMapper jsonMapper,
        final SoftenerProperties properties,
        final Clock clock
    ) {
        final HttpClient httpClient = HttpClient.newConnection()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = webClientBuilder.clone()
            .baseUrl(properties.cloud().baseUrl().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
        this.jsonMapper = jsonMapper;
        this.cloud = properties.cloud();
        this.clock = clock;
        this.loginGuard = new LoginGuard(clock);
    }

    //accessToken - null after a 401, the refresh token kept to take a new one; renewAt - when it is taken anew
    private record Token(String accessToken, String refreshToken, Instant expiresAt, Instant renewAt) {

        Token spent() {
            return new Token(null, refreshToken, expiresAt, renewAt);
        }
    }

    //the devices of the account (the first page - a house has one softener)
    //an answer without the list of data is not the model - a failed call, never an account without devices
    public Mono<List<IquaDevice>> devices() {
        return get("/devices", "the devices")
            .flatMap(root -> Mono.justOrEmpty(Optional.of(root.path("data")).filter(JsonNode::isArray))
                .switchIfEmpty(Mono.error(() -> new IquaException("The iQua cloud answered no list of the devices"))))
            .map(data -> StreamSupport.stream(data.spliterator(), false)
                .map(device -> device(device, "the devices"))
                .toList());
    }

    //the whole device with its properties; a summary alone - the account no longer has full access - is a failure, and so
    //is a device without its id or its properties ({} decodes into nulls - not the model)
    public Mono<IquaDevice> detail(final String deviceId) {
        return get("/devices/" + deviceId + "/detail-or-summary", "the softener")
            .flatMap(root -> Mono.justOrEmpty(Optional.of(root.path("device"))
                    .filter(JsonNode::isObject)
                    .map(device -> device(device, "the softener")))
                .switchIfEmpty(Mono.error(() -> new IquaException("The iQua cloud gave no detail of the softener"))))
            .filter(device -> device.id() != null && device.properties() != null)
            .switchIfEmpty(Mono.error(() -> new IquaException("The iQua cloud answered a softener without its properties")));
    }

    //a device as the record; one that does not convert is a fixed message - the message of Jackson quotes the answer
    private IquaDevice device(final JsonNode device, final String what) {
        try {
            return jsonMapper.convertValue(device, IquaDevice.class);
        } catch (final RuntimeException notTheModel) {
            throw new IquaException("The iQua cloud answered " + what + " not as their model");
        }
    }

    //one page of the regenerations of a device (HAS-398), as the cloud orders them; an answer without the list of data is
    //not the model
    public Mono<IquaRegenerationPage> regenerations(final String deviceId, final int page, final int perPage) {
        return call(value -> webClient.get()
                .uri(uri -> uri.path("/devices/" + deviceId + "/regeneration-events")
                    .queryParam("page", page)
                    .queryParam("per_page", perPage)
                    .build())
                .header("Authorization", "Bearer " + value)
                .accept(MediaType.APPLICATION_JSON), "the regenerations")
            .flatMap(root -> Mono.justOrEmpty(Optional.of(root.path("data")).filter(JsonNode::isArray))
                .switchIfEmpty(Mono.error(() -> new IquaException("The iQua cloud answered no list of the regenerations")))
                .map(data -> new IquaRegenerationPage(StreamSupport.stream(data.spliterator(), false)
                    .map(this::regeneration)
                    .toList(), root.path("total").asLong(0))));
    }

    private IquaRegeneration regeneration(final JsonNode event) {
        try {
            return jsonMapper.convertValue(event, IquaRegeneration.class);
        } catch (final RuntimeException notTheModel) {
            throw new IquaException("The iQua cloud answered the regenerations not as their model");
        }
    }

    private Mono<JsonNode> get(final String path, final String what) {
        return call(value -> webClient.get()
            .uri(uri -> uri.path(path).build())
            .header("Authorization", "Bearer " + value)
            .accept(MediaType.APPLICATION_JSON), what);
    }

    //with the token held, and once more with a new one when the cloud answers 401
    private Mono<JsonNode> call(final Function<String, WebClient.RequestHeadersSpec<?>> request, final String what) {
        return token()
            .flatMap(value -> exchange(request.apply(value), what))
            .onErrorResume(InvalidToken.class, invalid -> Mono.fromRunnable(this::spend)
                .then(token())
                .flatMap(value -> exchange(request.apply(value), what)))
            .onErrorMap(InvalidToken.class, invalid -> new IquaException("The iQua cloud refused a new token for " + what));
    }

    private void spend() {
        token.updateAndGet(held -> held == null ? null : held.spent());
    }

    private Mono<JsonNode> exchange(final WebClient.RequestHeadersSpec<?> request, final String what) {
        return request.retrieve()
            .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value(), response -> Mono.just(new InvalidToken()))
            .onStatus(HttpStatusCode::isError, response -> Mono.just(new IquaException(
                "The iQua cloud answered HTTP " + response.statusCode().value() + " for " + what, response.statusCode().value())))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new IquaException("The iQua cloud answered without a body for " + what)))
            .map(body -> tree(body, what))
            .onErrorMap(failure -> !(failure instanceof IquaException) && !(failure instanceof InvalidToken),
                failure -> new IquaException("The iQua cloud did not answer for " + what + ": "
                    + failure.getClass().getSimpleName()));
    }

    private Mono<String> token() {
        return Mono.defer(() -> Optional.ofNullable(token.get())
            .filter(held -> held.accessToken() != null)
            .filter(held -> held.renewAt().isAfter(clock.instant()))
            .map(held -> Mono.just(held.accessToken()))
            .orElseGet(this::newToken));
    }

    private Mono<String> newToken() {
        return Mono.defer(() -> taking.updateAndGet(current -> Optional.ofNullable(current)
            .orElseGet(() -> takeToken().doFinally(signal -> taking.set(null)).cache())));
    }

    //a refresh when there is a refresh token, a login when there is none or the refresh fails
    private Mono<String> takeToken() {
        return Mono.defer(() -> Optional.ofNullable(token.get())
                .map(Token::refreshToken)
                .map(refresh -> refresh(refresh).onErrorResume(IquaException.class, failed -> {
                    //a refresh token the cloud refused is not tried again
                    token.set(null);
                    log.info("The token of the iQua cloud could not be refreshed ({}), logging in", failed.getMessage());
                    return login();
                }))
                .orElseGet(this::login))
            .doOnNext(token::set)
            .map(Token::accessToken);
    }

    private Mono<Token> refresh(final String refreshToken) {
        return tokenCall("/auth/refresh", jsonMapper.createObjectNode().put(REFRESH_TOKEN, refreshToken), "a refresh")
            .doOnNext(held -> log.info("The token of the iQua cloud refreshed, valid until {}", held.expiresAt()));
    }

    private Mono<Token> login() {
        return Mono.defer(() -> loginGuard.heldUntil()
            .<Mono<Token>>map(until -> Mono.error(new IquaException("Logins to the iQua cloud are held back: until " + until
                + ", after " + LoginGuard.FAILURES + " failed ones")))
            .orElseGet(() -> tokenCall("/auth/login", jsonMapper.createObjectNode()
                    .put("email", cloud.email())
                    .put("password", cloud.password()), "a login")
                .doOnNext(held -> {
                    loginGuard.succeeded();
                    log.info("Logged in to the iQua cloud, the token valid until {}", held.expiresAt());
                })
                .doOnError(failure -> loginGuard.failed())));
    }

    private Mono<Token> tokenCall(final String path, final JsonNode body, final String what) {
        return webClient.post()
            .uri(uri -> uri.path(path).build())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> Mono.just(new IquaException(
                "The iQua cloud answered HTTP " + response.statusCode().value() + " for " + what)))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new IquaException("The iQua cloud answered without a body for " + what)))
            .map(answer -> tree(answer, what))
            .flatMap(root -> Mono.justOrEmpty(Optional.of(root)
                    .filter(answer -> !answer.path(ACCESS_TOKEN).asString("").isBlank())
                    .map(answer -> tokenOf(answer.path(ACCESS_TOKEN).asString(),
                        blankToNull(answer.path(REFRESH_TOKEN).asString("")))))
                .switchIfEmpty(Mono.error(() -> new IquaException("The iQua cloud gave no token for " + what))))
            .onErrorMap(failure -> !(failure instanceof IquaException),
                failure -> new IquaException("The iQua cloud did not answer for " + what + ": "
                    + failure.getClass().getSimpleName()));
    }

    //taken anew REFRESH_BEFORE its exp, or with a quarter of its life left when it lives four hours or less
    private Token tokenOf(final String accessToken, final String refreshToken) {
        final Instant expiresAt = expiryOf(accessToken);
        final Duration quarter = Duration.between(clock.instant(), expiresAt).dividedBy(REFRESH_SHARE);
        final Duration margin = quarter.compareTo(REFRESH_BEFORE) < 0 ? quarter : REFRESH_BEFORE;
        return new Token(accessToken, refreshToken, expiresAt, expiresAt.minus(margin));
    }

    //the exp of the JWT (seconds since the epoch, in its payload); a token that is not such a JWT, or whose exp is not
    //after now (a clock of the cloud ahead of the house), lives LIFETIME_WITHOUT_EXP - a 401 takes it anew before that
    Instant expiryOf(final String accessToken) {
        final Instant fallback = clock.instant().plus(LIFETIME_WITHOUT_EXP);
        final String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            return fallback;
        }
        try {
            final JsonNode payload = jsonMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8));
            return Optional.of(payload.path("exp"))
                .filter(JsonNode::isNumber)
                .map(exp -> Instant.ofEpochSecond(exp.asLong()))
                .filter(exp -> exp.isAfter(clock.instant()))
                .orElse(fallback);
        } catch (final RuntimeException notJwt) {
            return fallback;
        }
    }

    private static String blankToNull(final String value) {
        return value.isBlank() ? null : value;
    }

    private JsonNode tree(final String body, final String what) {
        try {
            return jsonMapper.readTree(body);
        } catch (final RuntimeException notJson) {
            throw new IquaException("The iQua cloud answered something that is not JSON for " + what);
        }
    }

    //a call the cloud refused for its token: tried once more with a new one
    private static final class InvalidToken extends RuntimeException {

        InvalidToken() {
            super("The token of the iQua cloud is not valid", null, false, false);
        }
    }
}
