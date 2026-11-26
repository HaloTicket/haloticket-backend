package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import com.haloticket.ticket_service.enums.TicketTypeStatus;

/** Kho loại vé của sự kiện, kèm các cập nhật tồn kho nguyên tử để tránh đọc rồi ghi gây xung đột. */
@Repository
public interface TicketTypeRepository extends JpaRepository<TicketType, String> {
    boolean existsByIdAndEventId(String id, String eventId);

    /** Các loại vé của một sự kiện. */
    List<TicketType> findByEventId(String eventId);
    /** Các loại vé của một suất diễn. */
    List<TicketType> findByShowtimeId(String showtimeId);
    /** Các loại vé của sự kiện thuộc một suất diễn. */
    List<TicketType> findByEventIdAndShowtimeId(String eventId, String showtimeId);

    /** Xóa mọi loại vé của một sự kiện. */
    @Modifying
    @Transactional
    void deleteByEventId(String eventId);

    /** Trừ 1 tồn kho còn lại khi phát hành vé; trả 0 dòng khi đã hết. */
    @Modifying
    @Query("UPDATE TicketType t SET t.totalQuantity = t.totalQuantity - 1, t.version = t.version + 1 WHERE t.id = :id AND t.totalQuantity > 0")
    int decrementTotalQuantityAtomic(@Param("id") String id);

    /**
     * Dòng cũ từ trước khi có {@code initial_quantity} chỉ có tồn kho còn lại (đã bị trừ). Phép đồng bộ của ban tổ chức tính
     * đã bán = ban đầu - còn lại nên ra 0 với dòng đó, khiến lần sửa đầu tiên đặt lại tồn kho và quên vé đã bán.
     * Hàm này ghi trước ban đầu = còn lại + đã phát hành để số đã bán đúng sau đó.
     */
    /** Điền {@code initial_quantity} cho dòng cũ còn thiếu; trả số dòng cập nhật. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE ticket_types SET initial_quantity = total_quantity + (SELECT COUNT(*) FROM tickets k WHERE k.ticket_type_id = ticket_types.id "
            + "AND k.status <> 'REVOKED' AND (k.acquisition_type IS NULL OR k.acquisition_type = 'PRIMARY')), version = version + 1 "
            + "WHERE id = :id AND initial_quantity IS NULL AND total_quantity IS NOT NULL", nativeQuery = true)
    int backfillInitialQuantityAtomic(@Param("id") String id);

    /** Sửa tồn kho của quản trị viên: đặt số còn lại bằng giá trị mới, chỉ khi vẫn đủ phủ số lượng đang giữ chỗ. */
    /** Đặt tồn kho còn lại về {@code total}; trả 0 dòng khi giá trị mới không đủ phủ phần đang giữ. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE TicketType t SET t.totalQuantity = :total, t.version = t.version + 1 "
            + "WHERE t.id = :id AND :total >= 0 AND COALESCE(t.reservedQuantity, 0) <= :total")
    int setTotalQuantityAtomic(@Param("id") String id, @Param("total") int total);

    /** Giữ chỗ chỉ khi tồn kho còn lại trừ phần đã giữ vẫn đủ số lượng và loại vé chưa bị ẩn; trả 0 dòng nếu không đủ. */
    /** Tăng số lượng giữ chỗ của loại vé nếu đủ tồn kho và trạng thái khác {@code hidden}. */
    @Modifying
    @Query("UPDATE TicketType t SET t.reservedQuantity = COALESCE(t.reservedQuantity, 0) + :qty, t.version = t.version + 1 "
            + "WHERE t.id = :id AND (t.ticketTypeStatus IS NULL OR t.ticketTypeStatus <> :hidden) "
            + "AND COALESCE(t.totalQuantity, 0) - COALESCE(t.reservedQuantity, 0) >= :qty")
    int reserveUnlessStatus(@Param("id") String id, @Param("qty") int qty, @Param("hidden") TicketTypeStatus hidden);

    /** Giữ chỗ {@code qty} vé của loại vé; trả 0 dòng khi không đủ hoặc loại vé đang ẩn. */
    default int reserveAtomic(String id, int qty) {
        return reserveUnlessStatus(id, qty, TicketTypeStatus.HIDDEN);
    }

    /** Trả lại {@code qty} vé đã giữ chỗ, không để số giữ chỗ xuống dưới 0. */
    @Modifying
    @Query("UPDATE TicketType t SET t.reservedQuantity = CASE WHEN COALESCE(t.reservedQuantity, 0) > :qty THEN COALESCE(t.reservedQuantity, 0) - :qty ELSE 0 END, "
            + "t.version = t.version + 1 WHERE t.id = :id")
    int releaseReservedAtomic(@Param("id") String id, @Param("qty") int qty);

    /**
     * Áp số lượng phát hành của ban tổ chức trong một câu lệnh, nên không có khoảng đọc rồi ghi so với
     * reserveAtomic và decrementTotalQuantityAtomic. Đã bán = ban đầu - còn lại (0 với dòng cũ có cột null);
     * tồn kho còn lại mới = số ban đầu mới - đã bán và phải còn đủ phủ phần booking chưa thanh toán đang giữ.
     * Đồng thời ghi các thuộc tính sửa được; trạng thái cố ý không đụng tới. Trả 0 dòng khi số mới quá thấp.
     */
    /** Thực thi cập nhật số lượng phát hành cùng thuộc tính loại vé; trả số dòng cập nhật. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TicketType t SET "
            + "t.totalQuantity = :newInitial - (CASE WHEN t.initialQuantity IS NULL OR t.totalQuantity IS NULL THEN 0 ELSE t.initialQuantity - t.totalQuantity END), "
            + "t.initialQuantity = :newInitial, t.name = :name, t.price = :price, t.isFree = :isFree, "
            + "t.minPerOrder = :minPerOrder, t.maxPerOrder = :maxPerOrder, t.description = :description, "
            + "t.updatedAt = :now, t.version = t.version + 1 "
            + "WHERE t.id = :id AND :newInitial - (CASE WHEN t.initialQuantity IS NULL OR t.totalQuantity IS NULL THEN 0 ELSE t.initialQuantity - t.totalQuantity END) >= COALESCE(t.reservedQuantity, 0)")
    int applyIssuedQuantityAtomic(@Param("id") String id, @Param("newInitial") int newInitial, @Param("name") String name,
            @Param("price") Double price, @Param("isFree") Boolean isFree, @Param("minPerOrder") Integer minPerOrder,
            @Param("maxPerOrder") Integer maxPerOrder, @Param("description") String description,
            @Param("now") LocalDateTime now);

    /** Cập nhật cùng các thuộc tính nhưng không đổi số lượng, cho yêu cầu đồng bộ không kèm số lượng. */
    /** Cập nhật tên, giá, giới hạn mua và mô tả của loại vé; trả số dòng cập nhật. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TicketType t SET t.name = :name, t.price = :price, t.isFree = :isFree, t.minPerOrder = :minPerOrder, "
            + "t.maxPerOrder = :maxPerOrder, t.description = :description, t.updatedAt = :now, t.version = t.version + 1 WHERE t.id = :id")
    int applyDetailsAtomic(@Param("id") String id, @Param("name") String name, @Param("price") Double price,
            @Param("isFree") Boolean isFree, @Param("minPerOrder") Integer minPerOrder,
            @Param("maxPerOrder") Integer maxPerOrder, @Param("description") String description,
            @Param("now") LocalDateTime now);

    /**
     * Chỉ lấy các loại vé có chênh lệch hoặc cần ghi chú, theo cùng logic phần A của scripts/audit/ticket_db_stock_reconciliation.sql.
     * Các cột: id, event_id, showtime_id, name, initial, remaining, held, issued, held_by_reservations.
     */
    /** Đối soát tồn kho: trả tối đa {@code limit} loại vé có số liệu lệch nhau. */
    @Query(value = "WITH issued AS (SELECT t.ticket_type_id AS tid, COUNT(*) AS n FROM tickets t "
            + "WHERE t.status <> 'REVOKED' AND (t.acquisition_type IS NULL OR t.acquisition_type = 'PRIMARY') GROUP BY t.ticket_type_id), "
            + "held AS (SELECT r.ticket_type_id AS tid, COALESCE(SUM(r.quantity), 0) AS n FROM ticket_stock_reservations r "
            + "WHERE r.status = 'HELD' GROUP BY r.ticket_type_id) "
            + "SELECT x.id, x.event_id, x.showtime_id, x.name, x.initial_quantity, x.total_quantity, x.held, x.issued, x.held_res FROM ("
            + "SELECT tt.id AS id, tt.event_id AS event_id, tt.showtime_id AS showtime_id, tt.name AS name, "
            + "tt.initial_quantity AS initial_quantity, tt.total_quantity AS total_quantity, COALESCE(tt.reserved_quantity, 0) AS held, "
            + "COALESCE(i.n, 0) AS issued, COALESCE(h.n, 0) AS held_res "
            + "FROM ticket_types tt LEFT JOIN issued i ON i.tid = tt.id LEFT JOIN held h ON h.tid = tt.id) x "
            + "WHERE x.held <> x.held_res "
            + "OR (x.initial_quantity IS NULL AND x.issued > 0) OR x.total_quantity IS NULL "
            + "OR x.total_quantity < 0 OR x.held > x.total_quantity OR x.total_quantity > x.initial_quantity "
            + "OR GREATEST(0, x.initial_quantity - x.total_quantity) <> x.issued "
            + "ORDER BY x.event_id, x.name, x.id LIMIT :limit", nativeQuery = true)
    List<Object[]> findStockDiscrepancies(@Param("limit") int limit);
}
