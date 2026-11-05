package cloud.cholewa.water.api;

import cloud.cholewa.water.command.ValveCommandService;
import cloud.cholewa.water.model.ValveCommandReply;
import cloud.cholewa.water.model.ValveReply;
import cloud.cholewa.water.service.ValveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

//The water valve (HAS-316) and its command (HAS-321), routed by the gateway under /home/water/** - no gateway release
@Slf4j
@RestController
@RequiredArgsConstructor
public class ValveController {

    private final ValveService valveService;
    private final ValveCommandService valveCommandService;

    @GetMapping("valve")
    Mono<ResponseEntity<ValveReply>> getValve() {
        return valveService.current()
            .doOnSubscribe(subscription -> log.info("Querying the water valve"))
            .map(ResponseEntity::ok);
    }

    //both read as text, so that a missing or wrong one is INVALID_COMMAND with its code - a typed parameter would be
    //refused by WebFlux without one
    @PostMapping("valve")
    Mono<ResponseEntity<ValveCommandReply>> command(
        @RequestParam(required = false) final String state,
        @RequestParam(required = false) final String source
    ) {
        return valveCommandService.command(state, source)
            .doOnSubscribe(subscription -> log.info("Commanding the water valve to {} by {}", state, source))
            .map(reply -> ResponseEntity.status(HttpStatus.ACCEPTED).body(reply));
    }
}
