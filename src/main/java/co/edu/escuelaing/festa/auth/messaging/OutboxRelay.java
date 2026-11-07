package co.edu.escuelaing.festa.auth.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Publica los eventos pendientes de la outbox esperando la confirmación del broker (publisher confirms). */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 50;
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit, Clock clock, MeterRegistry registry) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.clock = clock;
        this.published = Counter.builder("festa_outbox_published_total").register(registry);
        this.failed = Counter.builder("festa_outbox_failed_total").register(registry);
        Gauge.builder("festa_outbox_pending", outbox, OutboxRepository::countPending).register(registry);
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 3000)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outbox.lockPending(BATCH);
        for (OutboxEvent event : pending) {
            try {
                rabbit.invoke(ops -> {
                    ops.send(event.getExchange(), event.getRoutingKey(), toMessage(event));
                    ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                    return null;
                });
                event.markPublished(clock.instant());
                published.increment();
            } catch (Exception e) {
                event.markFailed(e.getMessage());
                failed.increment();
                log.warn("No se pudo publicar {} ({}); se reintenta", event.getRoutingKey(), event.getId(), e);
                break; // conserva el orden: el resto espera al siguiente ciclo
            }
        }
    }

    private static Message toMessage(OutboxEvent event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(event.getId().toString());
        props.setType(event.getRoutingKey());
        props.setAppId("auth-service");
        if (event.getCorrelationId() != null) {
            props.setHeader("x-correlation-id", event.getCorrelationId());
        }
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);
    }
}
