package co.edu.escuelaing.festa.auth.auth;

import co.edu.escuelaing.festa.auth.config.FestaProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** El primer admin no se puede crear desde la app: se siembra con ADMIN_EMAIL y ADMIN_PASSWORD. */
@Component
public class AdminSeeder implements ApplicationRunner {

    private final AuthService auth;
    private final FestaProperties.Admin admin;

    public AdminSeeder(AuthService auth, FestaProperties props) {
        this.auth = auth;
        this.admin = props.admin();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (admin != null && notBlank(admin.email()) && notBlank(admin.password())) {
            auth.seedAdmin(admin.email(), admin.password());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
