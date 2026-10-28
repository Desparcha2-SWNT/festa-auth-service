package co.edu.escuelaing.festa.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.escuelaing.festa.auth.TestSupport;
import co.edu.escuelaing.festa.auth.account.Account;
import co.edu.escuelaing.festa.auth.account.Role;
import co.edu.escuelaing.festa.auth.web.ApiException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {

    private final Instant now = Instant.parse("2026-10-24T15:00:00Z");
    private final JwtKeys keys = TestSupport.keys();
    private final RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
    private final TokenService service = new TokenService(
            new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.toJwk()))), keys, repo,
            TestSupport.props(null), Clock.fixed(now, ZoneOffset.UTC));

    private final Account account = new Account(UUID.randomUUID(), new byte[]{1}, "e", "n", Role.MERCHANT);

    @Test
    void accessTokenTraeLosClaimsAcordados() {
        String token = service.accessToken(account, "ana@escuelaing.edu.co");
        // El validador usa el mismo reloj fijo que el servicio; con el reloj real el token ya estaría vencido.
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(Clock.fixed(now, ZoneOffset.UTC));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(timestamps);
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo(account.getId().toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("ana@escuelaing.edu.co");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("merchant");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("festa-auth");
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(15 * 60));
        assertThat(jwt.getHeaders()).containsEntry("kid", keys.keyId()).containsEntry("alg", "RS256");
    }

    @Test
    void emiteRefreshOpacoYGuardaSoloSuHash() {
        var issued = service.issue(account, "a@b.co");
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repo).save(saved.capture());

        assertThat(issued.refreshToken()).hasSizeGreaterThan(40);
        assertThat(issued.expiresIn()).isEqualTo(900);
        assertThat(saved.getValue().getAccountId()).isEqualTo(account.getId());
    }

    @Test
    void consumirRotaElToken() {
        var token = new RefreshToken(UUID.randomUUID(), account.getId(), UUID.randomUUID(), new byte[]{9}, now.plusSeconds(3600));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(token));

        RefreshToken consumed = service.consume("raw");

        assertThat(consumed.isRevoked()).isTrue();
        verify(repo, never()).revokeFamily(any(), any());
    }

    @Test
    void reusarUnTokenRotadoRevocaTodaLaFamilia() {
        UUID family = UUID.randomUUID();
        var token = new RefreshToken(UUID.randomUUID(), account.getId(), family, new byte[]{9}, now.plusSeconds(3600));
        token.revoke(now.minusSeconds(10), null);
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.consume("raw"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_REFRESH_TOKEN");
        verify(repo).revokeFamily(eq(family), eq(now));
    }

    @Test
    void tokenVencidoNoSirve() {
        var token = new RefreshToken(UUID.randomUUID(), account.getId(), UUID.randomUUID(), new byte[]{9}, now.minusSeconds(1));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(token));
        assertThatThrownBy(() -> service.consume("raw")).isInstanceOf(ApiException.class);
    }

    @Test
    void tokenDesconocidoNoSirve() {
        when(repo.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consume("raw")).isInstanceOf(ApiException.class);
    }
}
