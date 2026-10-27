package co.edu.escuelaing.festa.auth.token;

import co.edu.escuelaing.festa.auth.account.Account;
import co.edu.escuelaing.festa.auth.config.FestaProperties;
import co.edu.escuelaing.festa.auth.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite access tokens (JWT RS256, cortos) y refresh tokens (opacos, rotan en cada uso).
 * Reusar un refresh token ya rotado revoca toda su familia: alguien más lo tiene.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final RefreshTokenRepository refreshTokens;
    private final FestaProperties.Jwt props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, JwtKeys keys, RefreshTokenRepository refreshTokens, FestaProperties props, Clock clock) {
        this.encoder = encoder;
        this.keys = keys;
        this.refreshTokens = refreshTokens;
        this.props = props.jwt();
        this.clock = clock;
    }

    public record IssuedTokens(String accessToken, String refreshToken, long expiresIn) {}

    /** Pareja nueva con una familia de refresh nueva (login/registro). */
    @Transactional
    public IssuedTokens issue(Account account, String email) {
        return issue(account, email, UUID.randomUUID());
    }

    /**
     * Rota el refresh token y devuelve la cuenta dueña. El llamador emite la pareja nueva con
     * {@link #issueInFamily} después de verificar que la cuenta sigue habilitada.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public RefreshToken consume(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(TokenService::invalidRefresh);
        if (token.isRevoked()) {
            int revoked = refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Reuso de refresh token detectado; familia {} revocada ({} tokens), cuenta {}",
                    token.getFamilyId(), revoked, token.getAccountId());
            throw invalidRefresh();
        }
        if (token.isExpired(now)) {
            throw invalidRefresh();
        }
        token.revoke(now, null);
        return token;
    }

    @Transactional
    public IssuedTokens issueInFamily(Account account, String email, RefreshToken previous) {
        IssuedTokens tokens = issue(account, email, previous.getFamilyId());
        return tokens;
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokens.findByTokenHash(hash(rawRefreshToken)).ifPresent(t -> {
            if (!t.isRevoked()) {
                refreshTokens.revokeFamily(t.getFamilyId(), clock.instant());
            }
        });
    }

    @Transactional
    public void revokeAll(UUID accountId) {
        refreshTokens.revokeAllForAccount(accountId, clock.instant());
    }

    /** Limpieza diaria de tokens vencidos hace más de 7 días. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = refreshTokens.deleteExpiredBefore(clock.instant().minus(7, ChronoUnit.DAYS));
        log.info("Refresh tokens vencidos eliminados: {}", deleted);
    }

    public String accessToken(Account account, String email) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(account.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(props.accessTtlMinutes())))
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("role", account.getRole().value())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.keyId()).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private IssuedTokens issue(Account account, String email, UUID familyId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = clock.instant().plus(Duration.ofDays(props.refreshTtlDays()));
        refreshTokens.save(new RefreshToken(UUID.randomUUID(), account.getId(), familyId, hash(refresh), expires));
        return new IssuedTokens(accessToken(account, email), refresh, props.accessTtlMinutes() * 60);
    }

    static byte[] hash(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "La sesión expiró. Inicia sesión de nuevo.");
    }
}
