package co.edu.escuelaing.festa.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.escuelaing.festa.auth.account.Account;
import co.edu.escuelaing.festa.auth.account.AccountRepository;
import co.edu.escuelaing.festa.auth.account.AccountStatus;
import co.edu.escuelaing.festa.auth.account.Role;
import co.edu.escuelaing.festa.auth.token.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    private final InboxHandler handler = new InboxHandler(processed, accounts, tokens, mapper,
            Validation.buildDefaultValidatorFactory().getValidator());

    private final Account account = new Account(UUID.randomUUID(), new byte[]{1}, "e", "n", Role.USER);

    private Envelope.Raw envelope(String type, String dataJson) throws Exception {
        return new Envelope.Raw(UUID.randomUUID(), type, 1, Instant.now(), "user-service", null, null, mapper.readTree(dataJson));
    }

    @Test
    void suspensionSuspendeYRevocaSesiones() throws Exception {
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        handler.handle(envelope(InboxHandler.USER_SUSPENDED, """
                {"userId":"%s","reason":"spam","decidedBy":"%s","decidedAt":"2026-10-26T12:00:00Z"}
                """.formatted(account.getId(), UUID.randomUUID())));

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(tokens).revokeAll(account.getId());
        verify(processed).save(any());
    }

    @Test
    void reactivacion() throws Exception {
        account.suspend(null);
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        handler.handle(envelope(InboxHandler.USER_REINSTATED, """
                {"userId":"%s","decidedBy":"%s","decidedAt":"2026-10-26T12:00:00Z"}
                """.formatted(account.getId(), UUID.randomUUID())));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void eventoDuplicadoSeIgnora() throws Exception {
        when(processed.existsById(any())).thenReturn(true);
        handler.handle(envelope(InboxHandler.USER_SUSPENDED, "{}"));
        verify(accounts, never()).findById(any());
        verify(processed, never()).save(any());
    }

    @Test
    void dataIncompletaEsErrorPermanente() throws Exception {
        var env = envelope(InboxHandler.USER_SUSPENDED, """
                {"userId":"%s","decidedBy":"%s","decidedAt":"2026-10-26T12:00:00Z"}
                """.formatted(account.getId(), UUID.randomUUID()));
        assertThatThrownBy(() -> handler.handle(env)).isInstanceOf(PermanentMessageException.class).hasMessageContaining("reason");
    }

    @Test
    void uuidMalFormadoEsErrorPermanente() throws Exception {
        var env = envelope(InboxHandler.USER_REINSTATED, """
                {"userId":"no-es-uuid","decidedBy":"x","decidedAt":"2026-10-26T12:00:00Z"}
                """);
        assertThatThrownBy(() -> handler.handle(env)).isInstanceOf(PermanentMessageException.class);
    }
}
