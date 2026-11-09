package com.akpedia.server.repository;

import com.akpedia.server.entity.Sector;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectorRepository extends JpaRepository<Sector, Long> {

    Optional<Sector> findByName(String name);

    List<Sector> findByNameIn(Collection<String> names);
}
