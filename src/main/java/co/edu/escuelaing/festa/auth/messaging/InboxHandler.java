package co.edu.escuelaing.festa.auth.messaging;

import co.edu.escuelaing.festa.auth.account.AccountRepository;
import co.edu.escuelaing.festa.auth.token.TokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Aplica los eventos que consume auth-service. Idempotente: el efecto y el registro del eventId van en la misma transacción. */
@Component
public class InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(InboxHandler.class);

    static final String USER_SUSPENDED = "moderation.user_suspended";
    static final String USER_REINSTATED = "moderation.user_reinstated";

    record UserSuspended(@NotNull UUID userId, UUID reportId, @NotNull String reason, Instant until,
                         @NotNull UUID decidedBy, @NotNull Instant decidedAt) {}

    record UserReinstated(@NotNull UUID userId, @NotNull UUID decidedBy, @NotNull Instant decidedAt) {}

    private final ProcessedEventRepository processed;
    private final AccountRepository accounts;
    private final TokenService tokens;
    private final ObjectMapper mapper;
    private final Validator validator;

    public InboxHandler(ProcessedEventRepository processed, AccountRepository accounts, TokenService tokens,
                        ObjectMapper mapper, Validator validator) {
        this.processed = processed;
        this.accounts = accounts;
        this.tokens = tokens;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Transactional
    public void handle(Envelope.Raw envelope) {
        if (processed.existsById(envelope.eventId())) {
            log.info("Evento {} ya procesado, se ignora", envelope.eventId());
            return;
        }
        switch (envelope.eventType()) {
            case USER_SUSPENDED -> onSuspended(read(envelope, UserSuspended.class));
            case USER_REINSTATED -> onReinstated(read(envelope, UserReinstated.class));
            default -> log.warn("Tipo de evento no esperado en la inbox: {}", envelope.eventType());
        }
        processed.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
    }

    private void onSuspended(UserSuspended e) {
        accounts.findById(e.userId()).ifPresentOrElse(account -> {
            account.suspend(e.until());
            tokens.revokeAll(account.getId());
            log.info("Cuenta {} suspendida hasta {}", account.getId(), e.until() == null ? "indefinido" : e.until());
        }, () -> log.warn("Suspensión de cuenta inexistente {}", e.userId()));
    }

    private void onReinstated(UserReinstated e) {
        accounts.findById(e.userId()).ifPresentOrElse(account -> {
            account.reinstate();
            log.info("Cuenta {} reactivada", account.getId());
        }, () -> log.warn("Reactivación de cuenta inexistente {}", e.userId()));
    }

    private <T> T read(Envelope.Raw envelope, Class<T> type) {
        T data;
        try {
            data = mapper.treeToValue(envelope.data(), type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PermanentMessageException(envelope.eventType() + ": data no cumple el contrato", e);
        }
        Set<ConstraintViolation<T>> violations = validator.validate(data);
        if (!violations.isEmpty()) {
            throw new PermanentMessageException(envelope.eventType() + ": " + violations.iterator().next().getPropertyPath() + " es obligatorio");
        }
        return data;
    }
}
