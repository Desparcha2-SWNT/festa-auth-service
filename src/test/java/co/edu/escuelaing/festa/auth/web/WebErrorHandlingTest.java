package co.edu.escuelaing.festa.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class WebErrorHandlingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void apiExceptionConservaEstadoCodigoYDetalles() {
        var res = handler.api(new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Ya existe", Map.of("field", "email")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().error().code()).isEqualTo("EMAIL_TAKEN");
        assertThat(res.getBody().error().details()).isEqualTo(Map.of("field", "email"));
    }

    @Test
    void validacionListaCadaCampo() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new FieldError("req", "email", "El correo es obligatorio."));
        var param = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);

        var res = handler.validation(new MethodArgumentNotValidException(param, binding));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.getBody().error().details())
                .isEqualTo(List.of(Map.of("field", "email", "message", "El correo es obligatorio.")));
    }

    @Test
    void erroresDelFrameworkYInesperadosTienenCodigoPropio() {
        assertThat(handler.unreadable(new HttpMessageNotReadableException("x", mock(HttpInputMessage.class)))
                .getBody().error().code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(handler.method(new HttpRequestMethodNotSupportedException("PATCH")).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.notFound(new NoResourceFoundException(HttpMethod.GET, "/nada")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        var res = handler.unexpected(new RuntimeException("detalle interno"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // El detalle interno no se filtra al cliente.
        assertThat(res.getBody().error().message()).doesNotContain("detalle interno");
    }

    @Test
    void seguridadResponde401Y403EnJson() throws Exception {
        var handlers = new JsonSecurityHandlers(mapper);

        var unauthorized = new MockHttpServletResponse();
        handlers.commence(new MockHttpServletRequest(), unauthorized, new BadCredentialsException("x"));
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getContentType()).startsWith("application/json");
        assertThat(mapper.readTree(unauthorized.getContentAsByteArray()).at("/error/code").asText()).isEqualTo("UNAUTHORIZED");

        var forbidden = new MockHttpServletResponse();
        handlers.handle(new MockHttpServletRequest(), forbidden, new AccessDeniedException("x"));
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(mapper.readTree(forbidden.getContentAsByteArray()).at("/error/code").asText()).isEqualTo("FORBIDDEN");
    }
}
