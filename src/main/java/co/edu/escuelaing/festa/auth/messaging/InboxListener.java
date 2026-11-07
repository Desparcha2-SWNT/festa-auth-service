package co.edu.escuelaing.festa.auth.messaging;

import co.edu.escuelaing.festa.auth.config.FestaProperties;
import co.edu.escuelaing.festa.auth.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Consume {@code auth-service.inbox}.
 * <ul>
 *   <li>Éxito o duplicado: ack.</li>
 *   <li>Error permanente (JSON inválido, contrato roto): nack sin requeue → DLQ.</li>
 *   <li>Error transitorio: se republica en {@code .retry} (vuelve en 10 s) con {@code x-retry-count}+1;
 *       al superar el máximo → DLQ.</li>
 * </ul>
 */
@Component
public class InboxListener {

    private static final Logger log = LoggerFactory.getLogger(InboxListener.class);
    static final String RETRY_HEADER = "x-retry-count";

    private final ObjectMapper mapper;
    private final InboxHandler handler;
    private final RabbitTemplate rabbit;
    private final MeterRegistry registry;
    private final String retryQueue;
    private final int maxRetries;

    public InboxListener(ObjectMapper mapper, InboxHandler handler, RabbitTemplate rabbit, MeterRegistry registry,
                         FestaProperties props) {
        this.mapper = mapper;
        this.handler = handler;
        this.rabbit = rabbit;
        this.registry = registry;
        this.retryQueue = props.messaging().inbox() + ".retry";
        this.maxRetries = props.messaging().maxRetries();
    }

    @RabbitListener(queues = "${festa.messaging.inbox}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String type = message.getMessageProperties().getType();
        Object correlation = message.getMessageProperties().getHeader("x-correlation-id");
        if (correlation != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlation.toString());
        }
        try {
            Envelope.Raw envelope = parse(message);
            type = envelope.eventType();
            handler.handle(envelope);
            channel.basicAck(tag, false);
            count(type, "ok");
        } catch (PermanentMessageException e) {
            log.error("Mensaje {} inválido, va a la DLQ: {}", type, e.getMessage());
            channel.basicNack(tag, false, false);
            count(type, "dead_letter");
        } catch (Exception e) {
            int retries = retryCount(message);
            if (retries >= maxRetries) {
                log.error("Mensaje {} agotó {} reintentos, va a la DLQ", type, retries, e);
                channel.basicNack(tag, false, false);
                count(type, "dead_letter");
            } else {
                log.warn("Error procesando {} (intento {}), se reintenta", type, retries + 1, e);
                scheduleRetry(message, retries + 1);
                channel.basicAck(tag, false);
                count(type, "retry");
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private Envelope.Raw parse(Message message) {
        try {
            Envelope.Raw envelope = mapper.readValue(message.getBody(), Envelope.Raw.class);
            if (envelope.eventId() == null || envelope.eventType() == null || envelope.data() == null) {
                throw new PermanentMessageException("Sobre incompleto");
            }
            return envelope;
        } catch (IOException e) {
            throw new PermanentMessageException("JSON inválido", e);
        }
    }

    private void scheduleRetry(Message original, int attempt) {
        Message retry = MessageBuilder.fromClonedMessage(original).setHeader(RETRY_HEADER, attempt).build();
        rabbit.invoke(ops -> {
            ops.send("", retryQueue, retry);
            ops.waitForConfirmsOrDie(5000);
            return null;
        });
    }

    private static int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(RETRY_HEADER);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private void count(String type, String outcome) {
        registry.counter("festa_messages_consumed_total", "type", String.valueOf(type), "outcome", outcome).increment();
    }
}
