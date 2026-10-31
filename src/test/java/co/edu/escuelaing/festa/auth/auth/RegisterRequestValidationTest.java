package co.edu.escuelaing.festa.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.escuelaing.festa.auth.auth.AuthDtos.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** El registro solo es válido si el titular autoriza el tratamiento de sus datos (Ley 1581 de 2012). */
class RegisterRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private RegisterRequest request(Boolean accepted) {
        return new RegisterRequest("ana@escuelaing.edu.co", "clave1234", "Ana Pérez", "user", accepted);
    }

    @Test
    void aceptaElRegistroConAutorizacion() {
        assertThat(validator.validate(request(true))).isEmpty();
    }

    @Test
    void rechazaElRegistroSiNoAutoriza() {
        var violations = validator.validate(request(false));
        assertThat(violations).extracting("message").contains(AuthDtos.PRIVACY_MESSAGE);
    }

    @Test
    void rechazaElRegistroSiNoEnviaLaAutorizacion() {
        var violations = validator.validate(request(null));
        assertThat(violations).extracting("message").contains(AuthDtos.PRIVACY_MESSAGE);
    }
}
