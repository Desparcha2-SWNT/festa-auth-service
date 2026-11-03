package co.edu.escuelaing.festa.auth.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Cuenta de Festa. Email y nombre se guardan cifrados; ver {@link co.edu.escuelaing.festa.auth.crypto.PiiCrypto}. */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "email_hash", nullable = false, unique = true)
    private byte[] emailHash;

    @Column(name = "email_enc", nullable = false)
    private String emailEnc;

    @Column(name = "display_name_enc", nullable = false)
    private String displayNameEnc;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "suspended_until")
    private Instant suspendedUntil;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "privacy_accepted_at")
    private Instant privacyAcceptedAt;

    @Column(name = "privacy_policy_version")
    private String privacyPolicyVersion;

    protected Account() {}

    public Account(UUID id, byte[] emailHash, String emailEnc, String displayNameEnc, Role role) {
        this.id = id;
        this.emailHash = emailHash;
        this.emailEnc = emailEnc;
        this.displayNameEnc = displayNameEnc;
        this.role = role;
    }

    /** Deja constancia de que el titular autorizó el tratamiento de sus datos (Ley 1581). */
    public void recordPrivacyConsent(Instant now, String policyVersion) {
        privacyAcceptedAt = now;
        privacyPolicyVersion = policyVersion;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Suspendida y la suspensión sigue vigente. Si ya venció, la reactiva. */
    public boolean isSuspended(Instant now) {
        if (status != AccountStatus.SUSPENDED) {
            return false;
        }
        if (suspendedUntil != null && !suspendedUntil.isAfter(now)) {
            reinstate();
            return false;
        }
        return true;
    }

    public void registerFailedLogin(int maxAttempts, Instant lockUntil) {
        failedLogins++;
        if (failedLogins >= maxAttempts) {
            lockedUntil = lockUntil;
            failedLogins = 0;
        }
    }

    public void registerSuccessfulLogin(Instant now) {
        failedLogins = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void suspend(Instant until) {
        status = AccountStatus.SUSPENDED;
        suspendedUntil = until;
    }

    public void reinstate() {
        status = AccountStatus.ACTIVE;
        suspendedUntil = null;
    }

    /** Borrado por Ley 1581: se eliminan los datos personales y se libera el email. */
    public void anonymize(byte[] randomHash, String placeholderEnc, Instant now) {
        status = AccountStatus.DELETED;
        emailHash = randomHash;
        emailEnc = placeholderEnc;
        displayNameEnc = placeholderEnc;
        passwordHash = null;
        googleSub = null;
        deletedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmailEnc() { return emailEnc; }
    public String getDisplayNameEnc() { return displayNameEnc; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getGoogleSub() { return googleSub; }
    public void setGoogleSub(String googleSub) { this.googleSub = googleSub; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public AccountStatus getStatus() { return status; }
    public Instant getSuspendedUntil() { return suspendedUntil; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPrivacyAcceptedAt() { return privacyAcceptedAt; }
    public String getPrivacyPolicyVersion() { return privacyPolicyVersion; }
}
