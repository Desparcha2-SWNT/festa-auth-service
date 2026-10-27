package co.edu.escuelaing.festa.auth.messaging;

import co.edu.escuelaing.festa.auth.account.Role;
import java.time.Instant;
import java.util.UUID;

/** Payloads ({@code data}) de los eventos que publica auth-service. Ver contracts/events/auth. */
public final class AuthEvents {

    private AuthEvents() {}

    public static final String ACCOUNT_REGISTERED = "account.registered";
    public static final String ACCOUNT_ROLE_CHANGED = "account.role_changed";
    public static final String ACCOUNT_DELETED = "account.deleted";

    public record AccountRegistered(UUID userId, String email, String displayName, String provider, Role role, Instant registeredAt) {}

    public record AccountRoleChanged(UUID userId, Role previousRole, Role newRole, UUID changedBy, Instant changedAt) {}

    public record AccountDeleted(UUID userId, Instant deletedAt) {}
}
