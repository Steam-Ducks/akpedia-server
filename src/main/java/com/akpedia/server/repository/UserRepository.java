package com.akpedia.server.repository;

import com.akpedia.server.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByKeycloakId(String keycloakId);

    boolean existsByEmail(String email);

    /** The id of the row linked to this Keycloak account, without loading the row. */
    @Query("SELECT u.id FROM User u WHERE u.keycloakId = :keycloakId")
    Optional<Long> findIdByKeycloakId(@Param("keycloakId") String keycloakId);

}
