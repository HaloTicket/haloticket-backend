package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Kho lưu người dùng, hỗ trợ thêm truy vấn động bằng Specification cho trang quản trị. */
@Repository
public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {
    /**
     * Tìm người dùng theo id Keycloak và khóa bản ghi để ghi, tránh hai luồng eKYC cập nhật cùng lúc.
     *
     * @param keycloakId id Keycloak
     * @return người dùng nếu có
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.keycloakId = :keycloakId")
    Optional<User> findForEkycUpdateByKeycloakId(@Param("keycloakId") String keycloakId);
    /**
     * Tìm người dùng theo email.
     *
     * @param email email cần tìm
     * @return người dùng nếu có
     */
    Optional<User> findByEmail(String email);
    /**
     * Tìm một người dùng bất kỳ đang dùng số điện thoại này, để kiểm tra trùng số.
     *
     * @param phoneNumber số điện thoại
     * @return người dùng nếu có
     */
    Optional<User> findFirstByPhoneNumber(String phoneNumber);
    /**
     * Tìm người dùng theo id Keycloak.
     *
     * @param keycloakId id Keycloak
     * @return người dùng nếu có
     */
    Optional<User> findByKeycloakId(String keycloakId);

    // vai trò và trạng thái được truyền bằng tham số, không bao giờ nối thành chuỗi trong truy vấn
    /**
     * Đếm người dùng giữ một vai trò và đang ở một trạng thái.
     *
     * @param role vai trò cần đếm
     * @param status trạng thái tài khoản
     * @return số người dùng
     */
    @Query("select count(u) from User u join u.roles r where r = :role and u.status = :status")
    long countByRoleAndStatus(@Param("role") Role role, @Param("status") UserStatus status);

    /**
     * Đếm số người dùng theo từng vai trò (người có hai vai trò được tính ở cả hai).
     *
     * @return danh sách cặp {@code [vai trò, số lượng]}
     */
    @Query("select r, count(u) from User u join u.roles r group by r")
    List<Object[]> countGroupedByRole();

    /**
     * Đếm số người dùng theo từng trạng thái tài khoản.
     *
     * @return danh sách cặp {@code [trạng thái, số lượng]}
     */
    @Query("select u.status, count(u) from User u group by u.status")
    List<Object[]> countGroupedByStatus();
    @Query("""
            select u.id from User u where u.status = :status and u.id > :after
            and (u.createdAt is null or u.createdAt <= :cutoff) order by u.id
            """)
    List<String> findActiveNotificationRecipientIds(@Param("status") UserStatus status,
            @Param("cutoff") java.time.LocalDateTime cutoff, @Param("after") String after, Pageable page);

    @Query("""
            select u.id from User u where u.status = :status and u.id in :ids
            and (u.createdAt is null or u.createdAt <= :cutoff) order by u.id
            """)
    List<String> matchActiveNotificationRecipientIds(@Param("status") UserStatus status,
            @Param("cutoff") java.time.LocalDateTime cutoff, @Param("ids") List<String> ids);

    @Query("""
            select u.id from User u where u.status = :status
            and (u.createdAt is null or u.createdAt <= :cutoff)
            and (u.keycloakId in :ids or (u.id in :ids and not exists
                 (select account.id from User account where account.keycloakId = u.id)))
            order by u.id
            """)
    List<String> matchActiveNotificationBuyerIds(@Param("status") UserStatus status,
            @Param("cutoff") java.time.LocalDateTime cutoff, @Param("ids") List<String> ids);
}
