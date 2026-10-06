package com.haloticket.event_service.controller;

import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.enums.CategoryStatus;
import com.haloticket.event_service.repository.EventCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/events/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final EventCategoryRepository categoryRepository;

    @GetMapping("/admin")
    public ResponseEntity<List<EventCategory>> getAllCategoriesAdmin() {
        return ResponseEntity.ok(categoryRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<EventCategory> createCategory(@RequestBody EventCategory category) {
        if (category.getStatus() == null) {
            category.setStatus(CategoryStatus.ACTIVE);
        }
        return ResponseEntity.ok(categoryRepository.save(category));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EventCategory> updateCategory(@PathVariable String id, @RequestBody EventCategory categoryDetails) {
        Optional<EventCategory> optionalCategory = categoryRepository.findById(id);
        if (optionalCategory.isPresent()) {
            EventCategory category = optionalCategory.get();
            category.setName(categoryDetails.getName());
            category.setDescription(categoryDetails.getDescription());
            category.setStatus(categoryDetails.getStatus());
            category.setSlug(categoryDetails.getSlug());
            category.setImageUrl(categoryDetails.getImageUrl());
            category.setActive(categoryDetails.isActive());
            
            return ResponseEntity.ok(categoryRepository.save(category));
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        // soft delete or just remove category from events to not delete events
        Optional<EventCategory> optionalCategory = categoryRepository.findById(id);
        if (optionalCategory.isPresent()) {
            EventCategory category = optionalCategory.get();
            category.setStatus(CategoryStatus.INACTIVE);
            categoryRepository.save(category);
        }
        return ResponseEntity.ok().build();
    }
}
