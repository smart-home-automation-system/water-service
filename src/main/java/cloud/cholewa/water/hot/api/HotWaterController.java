package cloud.cholewa.water.hot.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/hot")
@RequiredArgsConstructor
public class HotWaterController {

    @GetMapping
    Mono<ResponseEntity<String>> getHotWater() {
        log.info("Hot water requested");
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("Hot water not implemented yet"));
    }
}
