package com.akpedia.server.repository;

import com.akpedia.server.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByKeycloakId(String keycloakId);

    boolean existsByEmail(String email);

    /** The row with its sector loaded. */
    @Query("SELECT u FROM User u JOIN FETCH u.sector WHERE u.id = :id")
    Optional<User> findWithSectorById(@Param("id") Long id);

    /** Whether the row linked to this Keycloak account was disabled by an admin. */
    boolean existsByKeycloakIdAndActiveFalse(String keycloakId);

    /** The id of the row linked to this Keycloak account, without loading the row. */
    @Query("SELECT u.id FROM User u WHERE u.keycloakId = :keycloakId")
    Optional<Long> findIdByKeycloakId(@Param("keycloakId") String keycloakId);

    /**
     * The row of an account deleted from the log: still linked to it, or, after a backup was
     * restored over the deletion, the same row not yet deleted and linked to no other account.
     */
    @Query("""
            SELECT u FROM User u WHERE u.keycloakId = :keycloakId
            OR (u.id = :userId AND u.deletedAt IS NULL AND (u.keycloakId IS NULL OR u.keycloakId = :keycloakId))""")
    List<User> findDeletedAccountRows(@Param("keycloakId") String keycloakId, @Param("userId") Long userId);
}
