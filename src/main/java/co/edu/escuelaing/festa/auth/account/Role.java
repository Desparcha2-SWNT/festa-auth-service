package co.edu.escuelaing.festa.auth.account;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;

public enum Role {
    USER("user"),
    MERCHANT("merchant"),
    ADMIN("admin");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Role fromValue(String value) {
        return Arrays.stream(values()).filter(r -> r.value.equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Rol inválido: " + value));
    }

    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<Role, String> {
        @Override
        public String convertToDatabaseColumn(Role role) {
            return role == null ? null : role.value;
        }

        @Override
        public Role convertToEntityAttribute(String value) {
            return value == null ? null : fromValue(value);
        }
    }
}
