package co.edu.escuelaing.festa.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.escuelaing.festa.auth.account.Role;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.AccountView;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.AuthResponse;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.ChangePasswordRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.ChangeRoleRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.GoogleLoginRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.LoginRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.RefreshRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.RegisterRequest;
import co.edu.escuelaing.festa.auth.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;

class AuthControllerTest {

    private final AuthService auth = mock(AuthService.class);
    private final AuthController controller = new AuthController(auth);
    private final UUID me = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(me.toString()).build();
    private final AccountView view = new AccountView(me, "ana@escuelaing.edu.co", "Ana", Role.USER, "ACTIVE",
            true, false, Instant.parse("2026-11-01T10:00:00Z"));
    private final AuthResponse response = new AuthResponse("a", "r", "Bearer", 900, view, false);

    @Test
    void endpointsPublicosDelegaEnElServicio() {
        var reg = new RegisterRequest("ana@escuelaing.edu.co", "clave1234", "Ana", "user", true);
        when(auth.register(reg)).thenReturn(response);
        when(auth.login("ana@escuelaing.edu.co", "clave1234")).thenReturn(response);
        when(auth.loginWithGoogle("id-token", "merchant", true)).thenReturn(response);
        when(auth.refresh("r")).thenReturn(response);

        assertThat(controller.register(reg)).isSameAs(response);
        assertThat(controller.login(new LoginRequest("ana@escuelaing.edu.co", "clave1234"))).isSameAs(response);
        assertThat(controller.google(new GoogleLoginRequest("id-token", "merchant", true))).isSameAs(response);
        assertThat(controller.refresh(new RefreshRequest("r"))).isSameAs(response);
        controller.logout(new RefreshRequest("r"));
        verify(auth).logout("r");
    }

    @Test
    void endpointsAutenticadosUsanElSujetoDelJwt() {
        when(auth.me(me)).thenReturn(view);
        UUID target = UUID.randomUUID();
        when(auth.adminGet(target)).thenReturn(view);
        when(auth.changeRole(me, target, Role.MERCHANT)).thenReturn(view);

        assertThat(controller.me(jwt)).isSameAs(view);
        controller.changePassword(jwt, new ChangePasswordRequest("vieja123", "nueva1234"));
        verify(auth).changePassword(me, "vieja123", "nueva1234");
        controller.deleteMe(jwt);
        verify(auth).deleteAccount(me);
        assertThat(controller.adminGet(target)).isSameAs(view);
        assertThat(controller.changeRole(jwt, target, new ChangeRoleRequest(Role.MERCHANT))).isSameAs(view);
    }

    @Test
    void filtroDeCorrelacionReusaLaCabeceraOCreaUna() throws Exception {
        var filter = new CorrelationIdFilter();

        var req = new MockHttpServletRequest();
        req.addHeader(CorrelationIdFilter.HEADER, "kong-123");
        var res = new MockHttpServletResponse();
        String[] seen = new String[1];
        filter.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            }
        });
        assertThat(seen[0]).isEqualTo("kong-123");
        assertThat(res.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("kong-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        var longReq = new MockHttpServletRequest();
        longReq.addHeader(CorrelationIdFilter.HEADER, "x".repeat(101));
        var res2 = new MockHttpServletResponse();
        filter.doFilter(longReq, res2, new MockFilterChain());
        assertThat(UUID.fromString(res2.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    }
}
