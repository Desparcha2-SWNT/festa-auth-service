package co.edu.escuelaing.festa.auth.messaging;

/** El mensaje nunca se podrá procesar (mal formado, no cumple el contrato): va directo a la DLQ, sin reintentos. */
public class PermanentMessageException extends RuntimeException {
    public PermanentMessageException(String message) {
        super(message);
    }

    public PermanentMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
