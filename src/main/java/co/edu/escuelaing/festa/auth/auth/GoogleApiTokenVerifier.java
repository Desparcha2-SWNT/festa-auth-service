package co.edu.escuelaing.festa.auth.auth;

import co.edu.escuelaing.festa.auth.config.FestaProperties;
import co.edu.escuelaing.festa.auth.web.ApiException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Verifica contra las llaves públicas de Google (las cachea la librería). Audiencias = GOOGLE_CLIENT_IDS (Android, iOS, web). */
@Component
public class GoogleApiTokenVerifier implements GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleApiTokenVerifier.class);

    private final GoogleIdTokenVerifier verifier;

    public GoogleApiTokenVerifier(FestaProperties props) {
        List<String> clientIds = props.google().clientIds() == null ? List.of()
                : props.google().clientIds().stream().filter(s -> !s.isBlank()).toList();
        this.verifier = clientIds.isEmpty() ? null
                : new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(clientIds)
                        .setIssuers(List.of("https://accounts.google.com", "accounts.google.com"))
                        .build();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (verifier == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_NOT_CONFIGURED",
                    "El inicio de sesión con Google no está configurado.");
        }
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (Exception e) {
            log.warn("Fallo verificando ID token de Google: {}", e.getMessage());
            token = null;
        }
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "No se pudo validar tu cuenta de Google.");
        }
        GoogleIdToken.Payload p = token.getPayload();
        return new GoogleIdentity(p.getSubject(), p.getEmail(), Boolean.TRUE.equals(p.getEmailVerified()), (String) p.get("name"));
    }
}
