package cloud.cholewa.water.management.api;

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
@RequestMapping("/management")
@RequiredArgsConstructor
public class ManagementController {

    @GetMapping
    Mono<ResponseEntity<String>> getManagement() {
        log.info("Management requested");
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("Management not implemented yet"));
    }
}
