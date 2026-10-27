package co.edu.escuelaing.festa.auth.messaging;

import co.edu.escuelaing.festa.auth.config.FestaProperties;
import co.edu.escuelaing.festa.auth.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarda el evento en la outbox dentro de la transacción del llamador.
 * {@link OutboxRelay} lo publica después; si la transacción se revierte, el evento nunca existió.
 */
@Component
public class EventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String exchange;

    public EventPublisher(OutboxRepository outbox, ObjectMapper mapper, Clock clock, FestaProperties props) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
        this.exchange = props.messaging().exchange();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String eventType, Object data, UUID causationId) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        Envelope<Object> envelope = envelope(eventType, data, correlationId, causationId);
        try {
            outbox.save(new OutboxEvent(envelope.eventId(), exchange, eventType, mapper.writeValueAsString(envelope), correlationId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar " + eventType, e);
        }
        return envelope.eventId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String eventType, Object data) {
        return publish(eventType, data, null);
    }

    public Envelope<Object> envelope(String eventType, Object data, String correlationId, UUID causationId) {
        return new Envelope<>(UUID.randomUUID(), eventType, 1, clock.instant(), Envelope.PRODUCER, correlationId, causationId, data);
    }
}
