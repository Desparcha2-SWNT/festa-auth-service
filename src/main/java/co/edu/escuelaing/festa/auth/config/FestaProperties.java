package co.edu.escuelaing.festa.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "festa")
public record FestaProperties(
        Jwt jwt,
        Pii pii,
        Google google,
        String allowedEmailDomain,
        Login login,
        Admin admin,
        Messaging messaging) {

    public record Jwt(String issuer, String privateKeyPath, String publicKeyPath, long accessTtlMinutes, long refreshTtlDays) {}

    public record Pii(String key) {}

    public record Google(List<String> clientIds) {}

    public record Login(int maxFailedAttempts, long lockMinutes) {}

    public record Admin(String email, String password) {}

    public record Messaging(String exchange, String inbox, int maxRetries) {}
}
