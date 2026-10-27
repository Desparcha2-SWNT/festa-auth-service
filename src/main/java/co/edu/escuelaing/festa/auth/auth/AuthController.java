package co.edu.escuelaing.festa.auth.auth;

import co.edu.escuelaing.festa.auth.auth.AuthDtos.AccountView;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.AuthResponse;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.ChangePasswordRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.ChangeRoleRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.GoogleLoginRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.LoginRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.RefreshRequest;
import co.edu.escuelaing.festa.auth.auth.AuthDtos.RegisterRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest req) {
        return auth.loginWithGoogle(req.idToken(), req.accountType());
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
    }

    @GetMapping("/me")
    public AccountView me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(subject(jwt));
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(subject(jwt), req.currentPassword(), req.newPassword());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal Jwt jwt) {
        auth.deleteAccount(subject(jwt));
    }

    @GetMapping("/admin/accounts/{id}")
    public AccountView adminGet(@PathVariable UUID id) {
        return auth.adminGet(id);
    }

    @PatchMapping("/admin/accounts/{id}/role")
    public AccountView changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest req) {
        return auth.changeRole(subject(jwt), id, req.role());
    }

    private static UUID subject(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
