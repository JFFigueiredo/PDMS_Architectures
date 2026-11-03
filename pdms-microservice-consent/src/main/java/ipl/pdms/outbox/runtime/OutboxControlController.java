package ipl.pdms.outbox.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** One authenticated process-local flag; business JWTs do not grant control permission. */
@RestController
@RequestMapping("/actuator/outbox-control")
public class OutboxControlController {
    private final RuntimeOutboxControl control;
    private final String token;
    public OutboxControlController(RuntimeOutboxControl control, @Value("${pdms.outbox.admin-token:}") String token) {
        this.control = control; this.token = token;
    }
    private void authenticate(String supplied) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Management token is not configured");
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid management token");
    }
    @GetMapping
    public RuntimeOutboxControl.Status status(@RequestHeader(value="X-Outbox-Admin-Token", required=false) String supplied) {
        authenticate(supplied); return control.status();
    }
    @PatchMapping
    public RuntimeOutboxControl.Status change(@RequestHeader(value="X-Outbox-Admin-Token", required=false) String supplied,
                                             @RequestBody ObjectNode request) {
        authenticate(supplied); control.change(request); return control.status();
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason() == null ? "Runtime control failed" : error.getReason()));
    }
}
