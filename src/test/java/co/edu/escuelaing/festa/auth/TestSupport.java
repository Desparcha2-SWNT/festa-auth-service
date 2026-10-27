package co.edu.escuelaing.festa.auth;

import co.edu.escuelaing.festa.auth.config.FestaProperties;
import co.edu.escuelaing.festa.auth.token.JwtKeys;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;

public final class TestSupport {

    private TestSupport() {}

    public static final String PII_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    public static FestaProperties props(String allowedDomain) {
        return new FestaProperties(
                new FestaProperties.Jwt("festa-auth", null, null, 15, 30),
                new FestaProperties.Pii(PII_KEY),
                new FestaProperties.Google(List.of()),
                allowedDomain,
                new FestaProperties.Login(5, 15),
                new FestaProperties.Admin(null, null),
                new FestaProperties.Messaging("festa.auth", "auth-service.inbox", 3));
    }

    public static JwtKeys keys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            var pair = gen.generateKeyPair();
            return JwtKeys.of((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
