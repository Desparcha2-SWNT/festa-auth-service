package co.edu.escuelaing.festa.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.escuelaing.festa.auth.TestSupport;
import co.edu.escuelaing.festa.auth.account.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Verifica que lo que auth-service publica cumple los esquemas de festa-infra/contracts (única fuente de verdad).
 * Los contratos se ubican con -Dfesta.contracts.dir (el pom lo pone si existe ../festa-infra; el Dockerfile los copia).
 */
class ContractTest {

    private static Path contracts;
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @BeforeAll
    static void locate() {
        String dir = System.getProperty("festa.contracts.dir");
        contracts = dir == null || dir.isBlank() ? null : Path.of(dir);
        assumeTrue(contracts != null && Files.isDirectory(contracts.resolve("events")),
                "Contratos no disponibles (festa.contracts.dir); se omite el test de contrato");
    }

    static Stream<Arguments> publishedEvents() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-24T15:00:00.123456Z");
        return Stream.of(
                Arguments.of(AuthEvents.ACCOUNT_REGISTERED,
                        new AuthEvents.AccountRegistered(id, "ana@mail.escuelaing.edu.co", "Ana Pérez", "google", Role.MERCHANT, now)),
                Arguments.of(AuthEvents.ACCOUNT_ROLE_CHANGED,
                        new AuthEvents.AccountRoleChanged(id, Role.USER, Role.ADMIN, UUID.randomUUID(), now)),
                Arguments.of(AuthEvents.ACCOUNT_DELETED, new AuthEvents.AccountDeleted(id, now)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedEvents")
    void dataCumpleElEsquema(String eventType, Object data) throws Exception {
        JsonNode node = MAPPER.valueToTree(data);
        Set<ValidationMessage> errors = schema(contracts.resolve("events/auth/" + eventType + ".schema.json")).validate(node);
        assertThat(errors).as(eventType).isEmpty();
    }

    @ParameterizedTest(name = "sobre de {0}")
    @MethodSource("publishedEvents")
    void sobreCumpleElEsquema(String eventType, Object data) throws Exception {
        var publisher = new EventPublisher(null, MAPPER, Clock.systemUTC(), TestSupport.props(null));
        JsonNode node = MAPPER.valueToTree(publisher.envelope(eventType, data, "corr-1", null));
        // El sobre se serializa con Include.ALWAYS: correlationId/causationId nulos deben aparecer y ser válidos.
        assertThat(node.has("causationId")).isTrue();
        assertThat(schema(contracts.resolve("envelope.schema.json")).validate(node)).isEmpty();
    }

    private static JsonSchema schema(Path path) throws Exception {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return FACTORY.getSchema(Files.readString(path), config);
    }
}
