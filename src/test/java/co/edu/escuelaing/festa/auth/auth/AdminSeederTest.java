package co.edu.escuelaing.festa.auth.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import co.edu.escuelaing.festa.auth.TestSupport;
import co.edu.escuelaing.festa.auth.config.FestaProperties;
import org.junit.jupiter.api.Test;

class AdminSeederTest {

    private final AuthService auth = mock(AuthService.class);

    private FestaProperties props(String email, String password) {
        var base = TestSupport.props("escuelaing.edu.co");
        return new FestaProperties(base.jwt(), base.pii(), base.google(), base.allowedEmailDomain(), base.login(),
                new FestaProperties.Admin(email, password), base.messaging());
    }

    @Test
    void siembraElAdminConCorreoYClave() {
        new AdminSeeder(auth, props("admin@escuelaing.edu.co", "clave1234")).run(null);
        verify(auth).seedAdmin("admin@escuelaing.edu.co", "clave1234");
    }

    @Test
    void sinDatosCompletosNoSiembra() {
        new AdminSeeder(auth, props("admin@escuelaing.edu.co", " ")).run(null);
        new AdminSeeder(auth, props(null, "clave1234")).run(null);
        verify(auth, never()).seedAdmin(any(), any());
    }
}
