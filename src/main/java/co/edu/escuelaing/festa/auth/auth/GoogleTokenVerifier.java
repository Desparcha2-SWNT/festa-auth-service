package co.edu.escuelaing.festa.auth.auth;

/** Verifica un ID token de Google Sign-In (firma, audiencia, expiración). */
public interface GoogleTokenVerifier {

    record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {}

    /** @return la identidad, o lanza {@link co.edu.escuelaing.festa.auth.web.ApiException} si el token no es válido. */
    GoogleIdentity verify(String idToken);
}
