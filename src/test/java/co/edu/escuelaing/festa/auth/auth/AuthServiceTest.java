package co.edu.escuelaing.festa.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.escuelaing.festa.auth.TestSupport;
import co.edu.escuelaing.festa.auth.account.Account;
import co.edu.escuelaing.festa.auth.account.AccountRepository;
import co.edu.escuelaing.festa.auth.account.Role;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.RegisterRequest;
import co.edu.escuelaing.festa.auth.crypto.PiiCrypto;
import co.edu.escuelaing.festa.auth.messaging.AuthEvents;
import co.edu.escuelaing.festa.auth.messaging.EventPublisher;
import co.edu.escuelaing.festa.auth.token.TokenService;
import co.edu.escuelaing.festa.auth.web.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    private final Instant now = Instant.parse("2026-10-24T15:00:00Z");
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
    private final PiiCrypto crypto = new PiiCrypto(TestSupport.PII_KEY);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);

    private final AuthService service = new AuthService(accounts, crypto, passwords, tokens, events,
            new EmailPolicy(TestSupport.props("escuelaing.edu.co")), google, TestSupport.props("escuelaing.edu.co"),
            Clock.fixed(now, ZoneOffset.UTC), new SimpleMeterRegistry());

    {
        when(tokens.issue(any(), any())).thenReturn(new TokenService.IssuedTokens("access", "refresh", 900));
    }

    private Account existing(String email, String password) {
        var a = new Account(UUID.randomUUID(), crypto.blindIndex(email), crypto.encrypt(email), crypto.encrypt("Ana"), Role.USER);
        a.setPasswordHash(passwords.encode(password));
        when(accounts.findByEmailHash(eq(crypto.blindIndex(email)))).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void registroCifraDatosYPublicaAccountRegistered() {
        var res = service.register(new RegisterRequest("Ana@EscuelaIng.edu.co", "clave1234", "Ana Pérez", "merchant"));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmailEnc()).doesNotContain("ana");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.MERCHANT);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(AuthEvents.ACCOUNT_REGISTERED), data.capture());
        var registered = (AuthEvents.AccountRegistered) data.getValue();
        assertThat(registered.email()).isEqualTo("ana@escuelaing.edu.co");
        assertThat(registered.provider()).isEqualTo("password");

        assertThat(res.account().email()).isEqualTo("ana@escuelaing.edu.co");
        assertThat(res.newAccount()).isTrue();
    }

    @Test
    void registroRechazaOtrosDominios() {
        assertThatThrownBy(() -> service.register(new RegisterRequest("ana@gmail.com", "clave1234", "Ana", "user")))
                .extracting("code").isEqualTo("EMAIL_DOMAIN_NOT_ALLOWED");
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void registroRechazaCorreoRepetido() {
        when(accounts.existsByEmailHash(any())).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("ana@escuelaing.edu.co", "clave1234", "Ana", "user")))
                .extracting("code").isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void loginCorrecto() {
        existing("ana@escuelaing.edu.co", "clave1234");
        var res = service.login("ANA@escuelaing.edu.co", "clave1234");
        assertThat(res.accessToken()).isEqualTo("access");
        assertThat(res.newAccount()).isFalse();
    }

    @Test
    void correoInexistenteYClaveErradaDanElMismoError() {
        existing("ana@escuelaing.edu.co", "clave1234");
        assertThatThrownBy(() -> service.login("ana@escuelaing.edu.co", "mala1234")).extracting("code").isEqualTo("INVALID_CREDENTIALS");
        assertThatThrownBy(() -> service.login("nadie@escuelaing.edu.co", "mala1234")).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void bloqueaTrasCincoIntentosFallidos() {
        var account = existing("ana@escuelaing.edu.co", "clave1234");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login("ana@escuelaing.edu.co", "mala1234")).extracting("code").isEqualTo("INVALID_CREDENTIALS");
        }
        assertThatThrownBy(() -> service.login("ana@escuelaing.edu.co", "mala1234")).extracting("code").isEqualTo("ACCOUNT_LOCKED");
        // Bloqueada: ni con la clave correcta entra.
        assertThatThrownBy(() -> service.login("ana@escuelaing.edu.co", "clave1234")).extracting("code").isEqualTo("ACCOUNT_LOCKED");
        assertThat(account.getLockedUntil()).isEqualTo(now.plusSeconds(15 * 60));
    }

    @Test
    void cuentaSuspendidaNoEntra() {
        var account = existing("ana@escuelaing.edu.co", "clave1234");
        account.suspend(null);
        assertThatThrownBy(() -> service.login("ana@escuelaing.edu.co", "clave1234"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ACCOUNT_SUSPENDED");
    }

    @Test
    void suspensionVencidaSeLevantaSola() {
        var account = existing("ana@escuelaing.edu.co", "clave1234");
        account.suspend(now.minusSeconds(1));
        assertThat(service.login("ana@escuelaing.edu.co", "clave1234").accessToken()).isEqualTo("access");
    }

    @Test
    void googleCreaCuentaNuevaConRolPedido() {
        when(google.verify("tok")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-1", "Luis@escuelaing.edu.co", true, "Luis"));
        when(accounts.findByGoogleSub("g-1")).thenReturn(Optional.empty());
        when(accounts.findByEmailHash(any())).thenReturn(Optional.empty());

        var res = service.loginWithGoogle("tok", "merchant");

        assertThat(res.newAccount()).isTrue();
        assertThat(res.account().role()).isEqualTo(Role.MERCHANT);
        assertThat(res.account().hasGoogle()).isTrue();
        verify(events).publish(eq(AuthEvents.ACCOUNT_REGISTERED), any());
    }

    @Test
    void googleExigeCorreoVerificado() {
        when(google.verify("tok")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-1", "luis@escuelaing.edu.co", false, "Luis"));
        assertThatThrownBy(() -> service.loginWithGoogle("tok", null)).extracting("code").isEqualTo("GOOGLE_EMAIL_NOT_VERIFIED");
    }

    @Test
    void borrarCuentaAnonimizaRevocaYPublica() {
        var account = existing("ana@escuelaing.edu.co", "clave1234");
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));

        service.deleteAccount(account.getId());

        assertThat(account.getPasswordHash()).isNull();
        assertThat(crypto.decrypt(account.getEmailEnc())).isEqualTo("[eliminado]");
        verify(tokens).revokeAll(account.getId());
        verify(events).publish(eq(AuthEvents.ACCOUNT_DELETED), any());
    }

    @Test
    void adminNoPuedeCambiarSuPropioRol() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.changeRole(id, id, Role.USER)).extracting("code").isEqualTo("CANNOT_CHANGE_OWN_ROLE");
    }
}
