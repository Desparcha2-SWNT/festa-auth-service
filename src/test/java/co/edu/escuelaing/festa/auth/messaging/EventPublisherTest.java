package co.edu.escuelaing.festa.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import co.edu.escuelaing.festa.auth.TestSupport;
import co.edu.escuelaing.festa.auth.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

class EventPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");
    private final EventPublisher publisher = new EventPublisher(outbox, mapper, Clock.fixed(now, ZoneOffset.UTC),
            TestSupport.props("escuelaing.edu.co"));

    @AfterEach
    void limpiar() {
        MDC.clear();
    }

    @Test
    void guardaElSobreEnLaOutboxConCorrelacionYCausa() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-1");
        UUID cause = UUID.randomUUID();

        UUID id = publisher.publish("account.deleted", Map.of("userId", "u1"), cause);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        var e = saved.getValue();
        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getExchange()).isEqualTo("festa.auth");
        assertThat(e.getRoutingKey()).isEqualTo("account.deleted");
        assertThat(e.getCorrelationId()).isEqualTo("corr-1");
        var json = mapper.readTree(e.getPayload());
        assertThat(json.get("producer").asText()).isEqualTo("auth-service");
        assertThat(json.get("causationId").asText()).isEqualTo(cause.toString());
        assertThat(json.get("data").get("userId").asText()).isEqualTo("u1");
    }

    @Test
    void sinCausaNiCorrelacionLasDejaNulas() throws Exception {
        publisher.publish("account.registered", Map.of());

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getCorrelationId()).isNull();
        assertThat(mapper.readTree(saved.getValue().getPayload()).get("causationId").isNull()).isTrue();
    }

    @Test
    void datosNoSerializablesFallanConMensajeClaro() {
        assertThatThrownBy(() -> publisher.publish("x.y", new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x.y");
    }
}
