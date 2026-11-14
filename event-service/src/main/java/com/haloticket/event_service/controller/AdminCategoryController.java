package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.CategoryRequest;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.enums.CategoryStatus;
import com.haloticket.event_service.repository.EventCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Category administration. Reads are public (EventController); every write here is for the platform admin. */
@RestController
@RequestMapping("/api/events/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final EventCategoryRepository categoryRepository;

    @GetMapping("/admin")
    public ResponseEntity<List<EventCategory>> getAllCategoriesAdmin() {
        return ResponseEntity.ok(categoryRepository.findAllByOrderBySortOrderAscNameAsc());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<EventCategory> createCategory(@RequestBody CategoryRequest request) {
        String name = cleanName(request.getName());
        ensureUnique(name, request.getSlug(), null);
        EventCategory category = new EventCategory();
        category.setName(name);
        category.setDescription(request.getDescription());
        category.setSlug(blankToNull(request.getSlug()));
        category.setImageUrl(blankToNull(request.getImageUrl()));
        category.setIcon(blankToNull(request.getIcon()));
        category.setStatus(request.getStatus() != null ? request.getStatus() : CategoryStatus.ACTIVE);
        category.setActive(request.getActive() == null || request.getActive());
        category.setSortOrder(nextSortOrder());
        return ResponseEntity.ok(categoryRepository.save(category));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<EventCategory> updateCategory(@PathVariable String id, @RequestBody CategoryRequest request) {
        EventCategory category = categoryRepository.findById(id).orElse(null);
        if (category == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.getName() != null) {
            String name = cleanName(request.getName());
            ensureUnique(name, null, id);
            category.setName(name);
        }
        if (request.getSlug() != null) {
            ensureUnique(null, request.getSlug(), id);
            category.setSlug(blankToNull(request.getSlug()));
        }
        if (request.getDescription() != null) category.setDescription(request.getDescription());
        if (request.getImageUrl() != null) category.setImageUrl(blankToNull(request.getImageUrl()));
        if (request.getIcon() != null) category.setIcon(blankToNull(request.getIcon()));
        if (request.getStatus() != null) category.setStatus(request.getStatus());
        if (request.getActive() != null) category.setActive(request.getActive());
        return ResponseEntity.ok(categoryRepository.save(category));
    }

    /**
     * Saves the order of the categories in one call: the ids in the order they should be listed. Ids that are not listed keep
     * their relative order after the listed ones; an unknown id answers 400 and nothing changes.
     */
    @PutMapping("/order")
    @Transactional
    public ResponseEntity<List<EventCategory>> saveOrder(@RequestBody List<String> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CATEGORY_ORDER_EMPTY");
        }
        List<EventCategory> all = categoryRepository.findAllByOrderBySortOrderAscNameAsc();
        Map<String, EventCategory> byId = all.stream().collect(Collectors.toMap(EventCategory::getId, Function.identity()));
        Set<String> seen = new HashSet<>();
        int position = 0;
        for (String id : orderedIds) {
            EventCategory category = byId.get(id);
            if (category == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CATEGORY_UNKNOWN: " + id);
            }
            if (seen.add(id)) {
                category.setSortOrder(position++);
            }
        }
        for (EventCategory category : all) {
            if (!seen.contains(category.getId())) {
                category.setSortOrder(position++);
            }
        }
        categoryRepository.saveAll(all);
        return ResponseEntity.ok(categoryRepository.findAllByOrderBySortOrderAscNameAsc());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        // a category is switched off, not removed: the events keep pointing at it
        categoryRepository.findById(id).ifPresent(category -> {
            category.setStatus(CategoryStatus.INACTIVE);
            categoryRepository.save(category);
        });
        return ResponseEntity.ok().build();
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CATEGORY_NAME_REQUIRED");
        }
        return name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 409 when another category already has this name or slug (case does not matter); excludeId is the category being edited */
    private void ensureUnique(String name, String slug, String excludeId) {
        if (name != null) {
            categoryRepository.findByNameIgnoreCase(name).filter(c -> !c.getId().equals(excludeId)).ifPresent(c -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "CATEGORY_NAME_EXISTS");
            });
        }
        if (slug != null && !slug.isBlank()) {
            categoryRepository.findBySlugIgnoreCase(slug.trim()).filter(c -> !c.getId().equals(excludeId)).ifPresent(c -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "CATEGORY_SLUG_EXISTS");
            });
        }
    }

    private int nextSortOrder() {
        return categoryRepository.findAll().stream()
                .map(EventCategory::getSortOrder).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }
}
