package com.akpedia.server.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.CategoryResponse;
import com.akpedia.server.dto.SectorCategories;
import com.akpedia.server.dto.SectorCategoryMatrix;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.SectorNotFoundException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.SectorRepository;

/** Which categories each sector may see: the admin's permissions screen. */
@Service
public class SectorPermissionService {

    private final SectorRepository sectors;
    private final CategoryRepository categories;
    private final AuditService audit;

    public SectorPermissionService(SectorRepository sectors, CategoryRepository categories, AuditService audit) {
        this.sectors = sectors;
        this.categories = categories;
        this.audit = audit;
    }

    /** Every category against the realm's sectors, in the realm's order. */
    @Transactional(readOnly = true)
    public SectorCategoryMatrix matrix() {
        List<CategoryResponse> columns = categories.findAllByOrderByNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
        Map<String, Sector> byName = sectors.findByNameIn(AdminUserService.SECTORS).stream()
                .collect(Collectors.toMap(Sector::getName, Function.identity()));
        List<SectorCategories> rows = AdminUserService.SECTORS.stream()
                .map(byName::get)
                .filter(sector -> sector != null)
                .map(SectorPermissionService::row)
                .toList();
        return new SectorCategoryMatrix(columns, rows);
    }

    /**
     * Gives the sector exactly these categories. Recorded as {@code SECTOR_CATEGORIES_CHANGED}
     * when something changed.
     *
     * @throws SectorNotFoundException   if there is no such sector
     * @throws CategoryNotFoundException if a category does not exist
     */
    @Transactional
    public SectorCategories setCategories(Long sectorId, List<Long> categoryIds) {
        Sector sector = sectors.findById(sectorId).orElseThrow(() -> new SectorNotFoundException(sectorId));
        Set<Long> wanted = new LinkedHashSet<>(categoryIds);
        wanted.remove(null);
        List<Category> found = categories.findAllById(wanted);
        if (found.size() != wanted.size()) {
            Set<Long> foundIds = found.stream().map(Category::getId).collect(Collectors.toSet());
            Long missing = wanted.stream().filter(id -> !foundIds.contains(id)).findFirst().orElseThrow();
            throw new CategoryNotFoundException(missing);
        }

        List<String> before = names(sector.getCategories());
        List<String> after = names(found);
        if (!before.equals(after)) {
            sector.setCategories(new LinkedHashSet<>(found));
            sectors.flush();
            audit.record(null, AuditService.SECTOR_CATEGORIES_CHANGED, AuditService.SECTOR, sector.getId(),
                    AuditService.details("sector", sector.getName(), "before", before, "after", after));
        }
        return row(sector);
    }

    private static SectorCategories row(Sector sector) {
        List<Long> ids = sector.getCategories().stream().map(Category::getId).sorted().toList();
        return new SectorCategories(sector.getId(), sector.getName(), ids);
    }

    private static List<String> names(Collection<Category> list) {
        return list.stream().map(Category::getName).sorted(Comparator.naturalOrder()).toList();
    }
}
