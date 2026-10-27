package co.edu.escuelaing.festa.auth.auth;

import co.edu.escuelaing.festa.auth.account.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    static final String PASSWORD_RULE = "^(?=.*[A-Za-zÁÉÍÓÚáéíóúÑñ])(?=.*\\d).{8,72}$";
    static final String PASSWORD_MESSAGE = "La contraseña debe tener entre 8 y 72 caracteres, con al menos una letra y un número.";
    static final String ACCOUNT_TYPE_RULE = "^(user|merchant)$";
    static final String ACCOUNT_TYPE_MESSAGE = "El tipo de cuenta debe ser 'user' o 'merchant'.";

    public record RegisterRequest(
            @NotBlank(message = "El correo es obligatorio.") @Email(message = "El correo no es válido.") @Size(max = 254) String email,
            @NotBlank(message = "La contraseña es obligatoria.") @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String password,
            @NotBlank(message = "El nombre es obligatorio.") @Size(min = 2, max = 80, message = "El nombre debe tener entre 2 y 80 caracteres.") String displayName,
            @NotBlank(message = "El tipo de cuenta es obligatorio.") @Pattern(regexp = ACCOUNT_TYPE_RULE, message = ACCOUNT_TYPE_MESSAGE) String accountType) {}

    public record LoginRequest(
            @NotBlank(message = "El correo es obligatorio.") String email,
            @NotBlank(message = "La contraseña es obligatoria.") String password) {}

    public record GoogleLoginRequest(
            @NotBlank(message = "Falta el token de Google.") String idToken,
            @Pattern(regexp = ACCOUNT_TYPE_RULE, message = ACCOUNT_TYPE_MESSAGE) String accountType) {}

    public record RefreshRequest(@NotBlank(message = "Falta el refresh token.") String refreshToken) {}

    public record ChangePasswordRequest(
            String currentPassword,
            @NotBlank(message = "La contraseña nueva es obligatoria.") @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String newPassword) {}

    public record ChangeRoleRequest(@NotNull(message = "El rol es obligatorio.") Role role) {}

    public record AccountView(UUID id, String email, String displayName, Role role, String status,
                              boolean hasPassword, boolean hasGoogle, Instant createdAt) {}

    public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                               AccountView account, boolean newAccount) {}
}
