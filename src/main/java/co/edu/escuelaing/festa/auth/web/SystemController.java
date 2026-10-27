package co.edu.escuelaing.festa.auth.web;

import co.edu.escuelaing.festa.auth.token.JwtKeys;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {

    private final JdbcTemplate jdbc;
    private final ConnectionFactory rabbit;
    private final Map<String, Object> jwks;

    public SystemController(JdbcTemplate jdbc, ConnectionFactory rabbit, JwtKeys keys) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        RSAKey publicJwk = new RSAKey.Builder(keys.publicKey()).keyID(keys.keyId()).build();
        this.jwks = new JWKSet(publicJwk).toJSONObject(true);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", check(() -> jdbc.queryForObject("select 1", Integer.class)));
        checks.put("rabbitmq", check(() -> rabbit.createConnection().isOpen()));
        boolean ok = checks.values().stream().allMatch("ok"::equals);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ok" : "degraded");
        body.put("checks", checks);
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }

    /** Llave pública para que otros servicios verifiquen los JWT sin compartir archivos. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwks;
    }

    private static String check(Runnable probe) {
        try {
            probe.run();
            return "ok";
        } catch (Exception e) {
            return "down";
        }
    }
}
