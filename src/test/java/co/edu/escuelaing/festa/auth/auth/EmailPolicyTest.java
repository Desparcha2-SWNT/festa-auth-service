package co.edu.escuelaing.festa.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.escuelaing.festa.auth.TestSupport;
import org.junit.jupiter.api.Test;

class EmailPolicyTest {

    @Test
    void aceptaDominioYSubdominios() {
        var policy = new EmailPolicy(TestSupport.props("escuelaing.edu.co"));
        assertThat(policy.isAllowed("ana@escuelaing.edu.co")).isTrue();
        assertThat(policy.isAllowed("Ana@Mail.EscuelaIng.edu.co")).isTrue();
    }

    @Test
    void rechazaOtrosDominiosYTrucos() {
        var policy = new EmailPolicy(TestSupport.props("escuelaing.edu.co"));
        assertThat(policy.isAllowed("ana@gmail.com")).isFalse();
        assertThat(policy.isAllowed("ana@notescuelaing.edu.co")).isFalse();
        assertThat(policy.isAllowed("ana@escuelaing.edu.co.evil.com")).isFalse();
    }

    @Test
    void sinDominioConfiguradoAceptaTodo() {
        assertThat(new EmailPolicy(TestSupport.props("")).isAllowed("ana@gmail.com")).isTrue();
    }
}
