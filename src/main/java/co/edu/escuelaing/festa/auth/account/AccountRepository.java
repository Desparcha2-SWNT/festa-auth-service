package co.edu.escuelaing.festa.auth.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmailHash(byte[] emailHash);

    Optional<Account> findByGoogleSub(String googleSub);

    boolean existsByEmailHash(byte[] emailHash);
}
