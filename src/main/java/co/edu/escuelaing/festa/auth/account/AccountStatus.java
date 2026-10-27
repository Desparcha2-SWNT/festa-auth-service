package co.edu.escuelaing.festa.auth.account;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

public enum AccountStatus {
    ACTIVE, SUSPENDED, DELETED;

    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<AccountStatus, String> {
        @Override
        public String convertToDatabaseColumn(AccountStatus status) {
            return status == null ? null : status.name().toLowerCase();
        }

        @Override
        public AccountStatus convertToEntityAttribute(String value) {
            return value == null ? null : AccountStatus.valueOf(value.toUpperCase());
        }
    }
}
