package com.akpedia.server.repository;

import com.akpedia.server.entity.OptionalPermissionChoice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OptionalPermissionChoiceRepository extends JpaRepository<OptionalPermissionChoice, Long> {

    /** The user's choices in this version of the terms, oldest first: the last one per permission counts. */
    @Query("""
            SELECT c FROM OptionalPermissionChoice c
            WHERE c.user.id = :userId AND c.termsVersion.id = :termsVersionId
            ORDER BY c.chosenAt, c.id""")
    List<OptionalPermissionChoice> findByUserAndVersion(@Param("userId") Long userId,
            @Param("termsVersionId") Long termsVersionId);

    /** Everyone's choices in this version of the terms, oldest first, with the users. */
    @Query("""
            SELECT c FROM OptionalPermissionChoice c JOIN FETCH c.user
            WHERE c.termsVersion.id = :termsVersionId
            ORDER BY c.chosenAt, c.id""")
    List<OptionalPermissionChoice> findByVersion(@Param("termsVersionId") Long termsVersionId);

    /** Every choice the user made, oldest first, with the versions. */
    @Query("""
            SELECT c FROM OptionalPermissionChoice c JOIN FETCH c.termsVersion
            WHERE c.user.id = :userId ORDER BY c.chosenAt, c.id""")
    List<OptionalPermissionChoice> findByUser(@Param("userId") Long userId);
}
