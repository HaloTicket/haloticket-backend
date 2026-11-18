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

/** Quản trị danh mục sự kiện. Thao tác đọc là công khai (xem {@code EventController}); mọi thao tác ghi ở đây dành cho quản trị viên nền tảng. */
@RestController
@RequestMapping("/api/events/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final EventCategoryRepository categoryRepository;

    /**
     * Lấy toàn bộ danh mục (kể cả danh mục đã tắt), xếp theo thứ tự hiển thị rồi theo tên.
     * @return danh sách danh mục
     */
    @GetMapping("/admin")
    public ResponseEntity<List<EventCategory>> getAllCategoriesAdmin() {
        return ResponseEntity.ok(categoryRepository.findAllByOrderBySortOrderAscNameAsc());
    }

    /**
     * Tạo danh mục mới: tên phải khác rỗng và không trùng tên hoặc slug với danh mục khác; thứ tự hiển thị được đặt xuống cuối danh sách.
     * @param request dữ liệu danh mục; trạng thái mặc định {@code ACTIVE}, cờ {@code active} mặc định là bật
     * @return danh mục vừa tạo
     */
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

    /**
     * Cập nhật danh mục: chỉ ghi đè những trường có trong yêu cầu; tên hoặc slug mới phải còn duy nhất.
     * @param id mã danh mục cần sửa
     * @param request các trường cần đổi
     * @return danh mục sau khi sửa, hoặc 404 nếu không tìm thấy
     */
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
     * Lưu thứ tự các danh mục trong một lần gọi: danh sách mã theo đúng thứ tự muốn hiển thị. Danh mục không có trong danh sách giữ nguyên
     * thứ tự tương đối và xếp sau các danh mục đã liệt kê; mã không tồn tại trả 400 và không có gì thay đổi.
     * @param orderedIds danh sách mã danh mục theo thứ tự mới; không được rỗng
     * @return danh sách danh mục theo thứ tự đã lưu
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

    /**
     * Vô hiệu hóa danh mục (chuyển trạng thái sang {@code INACTIVE}) thay vì xóa; không có danh mục thì vẫn trả thành công.
     * @param id mã danh mục
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        // danh mục chỉ bị tắt chứ không bị xóa: các sự kiện vẫn trỏ tới nó
        categoryRepository.findById(id).ifPresent(category -> {
            category.setStatus(CategoryStatus.INACTIVE);
            categoryRepository.save(category);
        });
        return ResponseEntity.ok().build();
    }

    /**
     * Chuẩn hóa tên danh mục bằng cách cắt khoảng trắng hai đầu.
     * @param raw tên người dùng gửi lên
     * @return tên đã chuẩn hóa
     * @throws ResponseStatusException 400 {@code CATEGORY_NAME_REQUIRED} khi tên rỗng
     */
    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CATEGORY_NAME_REQUIRED");
        }
        return name;
    }

    /**
     * Đổi chuỗi rỗng hoặc toàn khoảng trắng thành {@code null}, ngược lại cắt khoảng trắng hai đầu.
     * @param value chuỗi đầu vào
     * @return chuỗi đã chuẩn hóa hoặc {@code null}
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Báo lỗi 409 khi danh mục khác đã dùng tên hoặc slug này (không phân biệt hoa thường).
     * @param name tên cần kiểm tra, {@code null} thì bỏ qua
     * @param slug slug cần kiểm tra, rỗng hoặc {@code null} thì bỏ qua
     * @param excludeId mã danh mục đang được sửa, loại khỏi phép so sánh
     * @throws ResponseStatusException 409 {@code CATEGORY_NAME_EXISTS} hoặc {@code CATEGORY_SLUG_EXISTS}
     */
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

    /**
     * Tính thứ tự hiển thị cho danh mục mới: lớn hơn thứ tự hiện có cao nhất một đơn vị, hoặc 0 khi chưa có danh mục nào.
     * @return thứ tự hiển thị kế tiếp
     */
    private int nextSortOrder() {
        return categoryRepository.findAll().stream()
                .map(EventCategory::getSortOrder).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }
}
