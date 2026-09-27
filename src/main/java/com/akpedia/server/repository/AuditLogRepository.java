package com.akpedia.server.repository;

import com.akpedia.server.entity.AuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByUserId(Long userId);
}
