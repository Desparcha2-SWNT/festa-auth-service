package co.edu.escuelaing.festa.auth.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Sobre común de festa-infra/contracts/envelope.schema.json. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Envelope<T>(
        UUID eventId,
        String eventType,
        int version,
        Instant occurredAt,
        String producer,
        String correlationId,
        UUID causationId,
        T data) {

    public static final String PRODUCER = "auth-service";

    /** Sobre entrante con {@code data} sin tipar todavía. */
    public record Raw(UUID eventId, String eventType, int version, Instant occurredAt, String producer,
                      String correlationId, UUID causationId, JsonNode data) {}
}
