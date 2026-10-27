package co.edu.escuelaing.festa.auth.auth;

import co.edu.escuelaing.festa.auth.config.FestaProperties;
import co.edu.escuelaing.festa.auth.crypto.PiiCrypto;
import co.edu.escuelaing.festa.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Restringe el registro al dominio del piloto (ALLOWED_EMAIL_DOMAIN). Acepta subdominios:
 * con {@code escuelaing.edu.co} pasan {@code a@escuelaing.edu.co} y {@code a@mail.escuelaing.edu.co}.
 * Vacío = cualquier dominio.
 */
@Component
public class EmailPolicy {

    private final String domain;

    public EmailPolicy(FestaProperties props) {
        String d = props.allowedEmailDomain();
        this.domain = d == null || d.isBlank() ? null : d.trim().toLowerCase().replaceFirst("^@", "");
    }

    public boolean isAllowed(String email) {
        if (domain == null) {
            return true;
        }
        String normalized = PiiCrypto.normalizeEmail(email);
        int at = normalized.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String host = normalized.substring(at + 1);
        return host.equals(domain) || host.endsWith("." + domain);
    }

    public void check(String email) {
        if (!isAllowed(email)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_DOMAIN_NOT_ALLOWED",
                    "Por ahora Festa solo está disponible para correos @" + domain + ".");
        }
    }
}
