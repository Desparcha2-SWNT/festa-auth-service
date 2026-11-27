package co.edu.escuelaing.festa.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import co.edu.escuelaing.festa.auth.TestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class InboxListenerTest {

    private static final String VALID = "{\"eventId\":\"3f1c2a40-1111-4b5a-9c1d-000000000001\","
            + "\"eventType\":\"user.suspended\",\"version\":1,\"occurredAt\":\"2026-11-01T10:00:00Z\","
            + "\"producer\":\"user-service\",\"data\":{\"userId\":\"x\"}}";

    private final InboxHandler handler = mock(InboxHandler.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final RabbitOperations ops = mock(RabbitOperations.class);
    private final Channel channel = mock(Channel.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InboxListener listener = new InboxListener(new ObjectMapper().registerModule(new JavaTimeModule()),
            handler, rabbit, registry, TestSupport.props("escuelaing.edu.co"));

    {
        doAnswer(inv -> inv.<RabbitOperations.OperationsCallback<?>>getArgument(0).doInRabbit(ops))
                .when(rabbit).invoke(any());
    }

    private static Message message(String body, Integer retries) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(7L);
        props.setHeader("x-correlation-id", "corr-1");
        if (retries != null) {
            props.setHeader(InboxListener.RETRY_HEADER, retries);
        }
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private double count(String outcome) {
        var c = registry.find("festa_messages_consumed_total").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void mensajeValidoSeProcesaYConfirma() throws Exception {
        listener.onMessage(message(VALID, null), channel);

        verify(handler).handle(any());
        verify(channel).basicAck(7L, false);
        assertThat(count("ok")).isEqualTo(1);
    }

    @Test
    void jsonInvalidoVaALaDlqSinReintento() throws Exception {
        listener.onMessage(message("no es json", null), channel);

        verify(handler, never()).handle(any());
        verify(channel).basicNack(7L, false, false);
        verify(rabbit, never()).invoke(any());
        assertThat(count("dead_letter")).isEqualTo(1);
    }

    @Test
    void sobreIncompletoVaALaDlq() throws Exception {
        listener.onMessage(message("{\"eventType\":\"user.suspended\"}", null), channel);

        verify(handler, never()).handle(any());
        verify(channel).basicNack(7L, false, false);
        assertThat(count("dead_letter")).isEqualTo(1);
    }

    @Test
    void errorTransitorioSeReprogramaEnRetryConContador() throws Exception {
        doThrow(new IllegalStateException("BD caída")).when(handler).handle(any());

        listener.onMessage(message(VALID, 1), channel);

        ArgumentCaptor<Message> retry = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(eq(""), eq("auth-service.inbox.retry"), retry.capture());
        verify(ops).waitForConfirmsOrDie(5000);
        assertThat((Object) retry.getValue().getMessageProperties().getHeader(InboxListener.RETRY_HEADER)).isEqualTo(2);
        verify(channel).basicAck(7L, false);
        assertThat(count("retry")).isEqualTo(1);
    }

    @Test
    void errorTransitorioQueAgotaReintentosVaALaDlq() throws Exception {
        doThrow(new IllegalStateException("BD caída")).when(handler).handle(any());

        listener.onMessage(message(VALID, 3), channel);

        verify(rabbit, never()).invoke(any());
        verify(channel).basicNack(7L, false, false);
        assertThat(count("dead_letter")).isEqualTo(1);
    }

    @Test
    void errorPermanenteDelHandlerVaALaDlq() throws Exception {
        doThrow(new PermanentMessageException("contrato roto")).when(handler).handle(any());

        listener.onMessage(message(VALID, null), channel);

        verify(channel).basicNack(7L, false, false);
        verify(rabbit, never()).invoke(any());
    }
}
