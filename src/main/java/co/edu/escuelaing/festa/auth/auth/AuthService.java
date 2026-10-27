package co.edu.escuelaing.festa.auth.auth;

import co.edu.escuelaing.festa.auth.account.Account;
import co.edu.escuelaing.festa.auth.account.AccountRepository;
import co.edu.escuelaing.festa.auth.account.AccountStatus;
import co.edu.escuelaing.festa.auth.account.Role;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.AccountView;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.AuthResponse;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.RegisterRequest;
import co.edu.escuelaing.festa.auth.auth.GoogleTokenVerifier.GoogleIdentity;
import co.edu.escuelaing.festa.auth.config.FestaProperties;
import co.edu.escuelaing.festa.auth.crypto.PiiCrypto;
import co.edu.escuelaing.festa.auth.messaging.AuthEvents;
import co.edu.escuelaing.festa.auth.messaging.EventPublisher;
import co.edu.escuelaing.festa.auth.token.RefreshToken;
import co.edu.escuelaing.festa.auth.token.TokenService;
import co.edu.escuelaing.festa.auth.token.TokenService.IssuedTokens;
import co.edu.escuelaing.festa.auth.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountRepository accounts;
    private final PiiCrypto crypto;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final EventPublisher events;
    private final EmailPolicy emailPolicy;
    private final GoogleTokenVerifier google;
    private final FestaProperties.Login loginProps;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public AuthService(AccountRepository accounts, PiiCrypto crypto, PasswordEncoder passwords, TokenService tokens,
                       EventPublisher events, EmailPolicy emailPolicy, GoogleTokenVerifier google, FestaProperties props,
                       Clock clock, MeterRegistry metrics) {
        this.accounts = accounts;
        this.crypto = crypto;
        this.passwords = passwords;
        this.tokens = tokens;
        this.events = events;
        this.emailPolicy = emailPolicy;
        this.google = google;
        this.loginProps = props.login();
        this.clock = clock;
        this.metrics = metrics;
        // Para que "email inexistente" tarde lo mismo que "contraseña incorrecta" (evita enumerar cuentas).
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    // ---------------------------------------------------------------- registro / login

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = PiiCrypto.normalizeEmail(req.email());
        emailPolicy.check(email);
        byte[] emailHash = crypto.blindIndex(email);
        if (accounts.existsByEmailHash(emailHash)) {
            throw emailTaken();
        }
        Role role = Role.fromValue(req.accountType());
        Account account = new Account(UUID.randomUUID(), emailHash, crypto.encrypt(email), crypto.encrypt(req.displayName().trim()), role);
        account.setPasswordHash(passwords.encode(req.password()));
        account.registerSuccessfulLogin(clock.instant());
        saveNew(account);
        publishRegistered(account, email, req.displayName().trim(), "password");
        count("register", "password", "ok");
        return respond(account, email, tokens.issue(account, email), true);
    }

    /** Las fallas de contraseña se guardan aunque se lance la excepción (noRollbackFor). */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse login(String rawEmail, String password) {
        String email = PiiCrypto.normalizeEmail(rawEmail);
        Account account = accounts.findByEmailHash(crypto.blindIndex(email)).orElse(null);
        Instant now = clock.instant();

        if (account == null || account.getPasswordHash() == null) {
            passwords.matches(password, dummyHash);
            count("login", "password", "invalid");
            throw invalidCredentials();
        }
        if (account.isLocked(now)) {
            count("login", "password", "locked");
            throw locked(account);
        }
        if (!passwords.matches(password, account.getPasswordHash())) {
            account.registerFailedLogin(loginProps.maxFailedAttempts(), now.plus(Duration.ofMinutes(loginProps.lockMinutes())));
            count("login", "password", "invalid");
            if (account.isLocked(now)) {
                log.warn("Cuenta {} bloqueada por intentos fallidos", account.getId());
                throw locked(account);
            }
            throw invalidCredentials();
        }
        ensureCanLogin(account, now);
        account.registerSuccessfulLogin(now);
        count("login", "password", "ok");
        return respond(account, email, tokens.issue(account, email), false);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse loginWithGoogle(String idToken, String accountType) {
        GoogleIdentity identity = google.verify(idToken);
        if (!identity.emailVerified() || identity.email() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "GOOGLE_EMAIL_NOT_VERIFIED", "Tu correo de Google no está verificado.");
        }
        String email = PiiCrypto.normalizeEmail(identity.email());
        emailPolicy.check(email);
        Instant now = clock.instant();

        Account account = accounts.findByGoogleSub(identity.subject())
                .or(() -> accounts.findByEmailHash(crypto.blindIndex(email)))
                .orElse(null);

        boolean isNew = account == null;
        if (isNew) {
            String name = identity.name() == null || identity.name().isBlank() ? email.substring(0, email.indexOf('@')) : identity.name();
            Role role = accountType == null ? Role.USER : Role.fromValue(accountType);
            account = new Account(UUID.randomUUID(), crypto.blindIndex(email), crypto.encrypt(email), crypto.encrypt(name), role);
            account.setGoogleSub(identity.subject());
            saveNew(account);
            publishRegistered(account, email, name, "google");
        } else if (account.getGoogleSub() == null) {
            // Cuenta creada con contraseña y mismo correo verificado por Google: se vinculan.
            account.setGoogleSub(identity.subject());
            log.info("Cuenta {} vinculada con Google", account.getId());
        }
        ensureCanLogin(account, now);
        account.registerSuccessfulLogin(now);
        count(isNew ? "register" : "login", "google", "ok");
        return respond(account, email, tokens.issue(account, email), isNew);
    }

    /**
     * Sin @Transactional a propósito: {@link TokenService#consume} confirma su propia transacción,
     * así la revocación por reuso queda guardada aunque luego se responda 401.
     */
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken previous = tokens.consume(rawRefreshToken);
        Account account = accounts.findById(previous.getAccountId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "La sesión expiró. Inicia sesión de nuevo."));
        ensureCanLogin(account, clock.instant());
        String email = crypto.decrypt(account.getEmailEnc());
        return respond(account, email, tokens.issueInFamily(account, email, previous), false);
    }

    public void logout(String rawRefreshToken) {
        tokens.revoke(rawRefreshToken);
    }

    // ---------------------------------------------------------------- cuenta propia

    @Transactional(readOnly = true)
    public AccountView me(UUID accountId) {
        return view(load(accountId));
    }

    @Transactional
    public void changePassword(UUID accountId, String currentPassword, String newPassword) {
        Account account = load(accountId);
        if (account.getPasswordHash() != null
                && (currentPassword == null || !passwords.matches(currentPassword, account.getPasswordHash()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "La contraseña actual no es correcta.");
        }
        account.setPasswordHash(passwords.encode(newPassword));
        tokens.revokeAll(accountId);
    }

    /** Derecho de supresión (Ley 1581): anonimiza la cuenta, cierra sesiones y avisa a los demás servicios. */
    @Transactional
    public void deleteAccount(UUID accountId) {
        Account account = load(accountId);
        Instant now = clock.instant();
        byte[] randomHash = new byte[32];
        random.nextBytes(randomHash);
        account.anonymize(randomHash, crypto.encrypt("[eliminado]"), now);
        tokens.revokeAll(accountId);
        events.publish(AuthEvents.ACCOUNT_DELETED, new AuthEvents.AccountDeleted(accountId, now));
        log.info("Cuenta {} eliminada por solicitud del titular", accountId);
    }

    // ---------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public AccountView adminGet(UUID accountId) {
        return view(load(accountId));
    }

    @Transactional
    public AccountView changeRole(UUID adminId, UUID accountId, Role newRole) {
        if (adminId.equals(accountId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_CHANGE_OWN_ROLE", "No puedes cambiar tu propio rol.");
        }
        Account account = load(accountId);
        Role previous = account.getRole();
        if (previous != newRole) {
            account.setRole(newRole);
            events.publish(AuthEvents.ACCOUNT_ROLE_CHANGED,
                    new AuthEvents.AccountRoleChanged(accountId, previous, newRole, adminId, clock.instant()));
            log.info("Rol de {} cambiado de {} a {} por {}", accountId, previous.value(), newRole.value(), adminId);
        }
        return view(account);
    }

    /** Crea el primer admin desde ADMIN_EMAIL/ADMIN_PASSWORD si no existe. */
    @Transactional
    public void seedAdmin(String rawEmail, String password) {
        String email = PiiCrypto.normalizeEmail(rawEmail);
        if (accounts.existsByEmailHash(crypto.blindIndex(email))) {
            return;
        }
        Account admin = new Account(UUID.randomUUID(), crypto.blindIndex(email), crypto.encrypt(email), crypto.encrypt("Admin Festa"), Role.ADMIN);
        admin.setPasswordHash(passwords.encode(password));
        saveNew(admin);
        publishRegistered(admin, email, "Admin Festa", "password");
        log.info("Admin inicial creado ({})", admin.getId());
    }

    // ---------------------------------------------------------------- helpers

    private void ensureCanLogin(Account account, Instant now) {
        if (account.getStatus() == AccountStatus.DELETED) {
            throw invalidCredentials();
        }
        if (account.isSuspended(now)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "Tu cuenta está suspendida.",
                    account.getSuspendedUntil() == null ? null : Map.of("until", account.getSuspendedUntil()));
        }
    }

    private void saveNew(Account account) {
        try {
            accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
    }

    private void publishRegistered(Account account, String email, String name, String provider) {
        events.publish(AuthEvents.ACCOUNT_REGISTERED,
                new AuthEvents.AccountRegistered(account.getId(), email, name, provider, account.getRole(), clock.instant()));
    }

    private Account load(UUID accountId) {
        return accounts.findById(accountId)
                .filter(a -> a.getStatus() != AccountStatus.DELETED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "La cuenta no existe."));
    }

    private AuthResponse respond(Account account, String email, IssuedTokens issued, boolean isNew) {
        return new AuthResponse(issued.accessToken(), issued.refreshToken(), "Bearer", issued.expiresIn(),
                view(account, email), isNew);
    }

    private AccountView view(Account account) {
        return view(account, crypto.decrypt(account.getEmailEnc()));
    }

    private AccountView view(Account account, String email) {
        return new AccountView(account.getId(), email, crypto.decrypt(account.getDisplayNameEnc()), account.getRole(),
                account.getStatus().name().toLowerCase(), account.getPasswordHash() != null, account.getGoogleSub() != null,
                account.getCreatedAt());
    }

    private void count(String action, String method, String outcome) {
        metrics.counter("festa_auth_attempts_total", "action", action, "method", method, "outcome", outcome).increment();
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos.");
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Ya existe una cuenta con ese correo.");
    }

    private static ApiException locked(Account account) {
        return new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                "Demasiados intentos fallidos. Intenta de nuevo más tarde.", Map.of("until", account.getLockedUntil()));
    }
}
