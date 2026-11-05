package cloud.cholewa.water.api;

import cloud.cholewa.water.model.ValveReply;
import cloud.cholewa.water.service.ValveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

//The water valve (HAS-316), routed by the gateway under /home/water/** - no gateway release
@Slf4j
@RestController
@RequiredArgsConstructor
public class ValveController {

    private final ValveService valveService;

    @GetMapping("valve")
    Mono<ResponseEntity<ValveReply>> getValve() {
        return valveService.current()
            .doOnSubscribe(subscription -> log.info("Querying the water valve"))
            .map(ResponseEntity::ok);
    }
}
