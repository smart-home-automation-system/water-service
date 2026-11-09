package cloud.cholewa.water.api;

import cloud.cholewa.water.model.SoftenerReply;
import cloud.cholewa.water.service.SoftenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

//The water softener from the iQua cloud (HAS-397), routed by the gateway under /home/water/** - no gateway release
@Slf4j
@RestController
@RequiredArgsConstructor
public class SoftenerController {

    private final SoftenerService softenerService;

    @GetMapping("softener")
    Mono<ResponseEntity<SoftenerReply>> getSoftener() {
        return softenerService.current()
            .doOnSubscribe(subscription -> log.info("Querying the water softener"))
            .map(ResponseEntity::ok);
    }
}
