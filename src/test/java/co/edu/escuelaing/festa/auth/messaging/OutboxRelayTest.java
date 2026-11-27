package co.edu.escuelaing.festa.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OutboxRelayTest {

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final RabbitOperations ops = mock(RabbitOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(outbox, rabbit, Clock.fixed(now, ZoneOffset.UTC), registry);

    {
        doAnswer(inv -> inv.<RabbitOperations.OperationsCallback<?>>getArgument(0).doInRabbit(ops))
                .when(rabbit).invoke(any());
    }

    private static OutboxEvent event(String key, String correlation) {
        return new OutboxEvent(UUID.randomUUID(), "festa.auth", key, "{\"a\":1}", correlation);
    }

    @Test
    void publicaPendientesConPropiedadesYConfirmacion() {
        var e = event("account.registered", "corr-9");
        when(outbox.lockPending(50)).thenReturn(List.of(e));

        relay.relay();

        ArgumentCaptor<Message> msg = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(eq("festa.auth"), eq("account.registered"), msg.capture());
        verify(ops).waitForConfirmsOrDie(5000);
        var props = msg.getValue().getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(e.getId().toString());
        assertThat(props.getType()).isEqualTo("account.registered");
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((Object) props.getHeader("x-correlation-id")).isEqualTo("corr-9");
        assertThat(new String(msg.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
        assertThat(registry.counter("festa_outbox_published_total").count()).isEqualTo(1);
    }

    @Test
    void sinCorrelacionNoAgregaCabecera() {
        when(outbox.lockPending(50)).thenReturn(List.of(event("account.deleted", null)));

        relay.relay();

        ArgumentCaptor<Message> msg = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(any(), any(), msg.capture());
        assertThat(msg.getValue().getMessageProperties().getHeaders()).doesNotContainKey("x-correlation-id");
    }

    @Test
    void unFalloDetieneElLoteParaConservarElOrden() {
        var first = event("account.registered", null);
        var second = event("account.deleted", null);
        when(outbox.lockPending(50)).thenReturn(List.of(first, second));
        doThrow(new AmqpException("sin confirmación")).when(ops).waitForConfirmsOrDie(5000);

        relay.relay();

        verify(ops, times(1)).send(any(), any(), any(Message.class));
        assertThat(registry.counter("festa_outbox_failed_total").count()).isEqualTo(1);
        assertThat(registry.counter("festa_outbox_published_total").count()).isZero();
    }

    @Test
    void elGaugeReportaLosPendientes() {
        when(outbox.countPending()).thenReturn(4L);
        assertThat(registry.get("festa_outbox_pending").gauge().value()).isEqualTo(4.0);
    }
}
