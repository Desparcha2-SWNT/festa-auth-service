package co.edu.escuelaing.festa.auth.messaging;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** SKIP LOCKED permite varias réplicas del servicio sin publicar dos veces el mismo mensaje. */
    @Query(value = """
            SELECT * FROM outbox
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPending(int limit);

    @Query(value = "SELECT count(*) FROM outbox WHERE published_at IS NULL", nativeQuery = true)
    long countPending();
}
