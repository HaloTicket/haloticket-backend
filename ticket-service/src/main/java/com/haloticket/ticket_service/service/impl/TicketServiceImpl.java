package com.haloticket.ticket_service.service.impl;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.OwnedTicketResponse;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.util.TicketQuantities;
import com.haloticket.ticket_service.entity.SeatLayout;
import com.haloticket.ticket_service.entity.SeatZone;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.exception.QuantityBelowSoldAndHeldException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.haloticket.ticket_service.enums.TicketTypeStatus;
import com.haloticket.ticket_service.repository.SeatLayoutRepository;
import com.haloticket.ticket_service.repository.SeatZoneRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cài đặt {@code TicketService}: quản lý loại vé (tạo, đồng bộ từ ban tổ chức, tra cứu), sơ đồ ghế, trạng thái ghế lấy từ Redis
 * và danh sách vé của người dùng.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketServiceImpl implements TicketService {

    private final TicketTypeRepository ticketTypeRepository;
    private final TicketRepository ticketRepository;
    private final SeatLayoutRepository seatLayoutRepository;
    private final SeatZoneRepository seatZoneRepository;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Tạo một loại vé mới từ yêu cầu.
     *
     * @param request dữ liệu loại vé; null thì không làm gì
     * @return loại vé đã lưu, hoặc null khi yêu cầu null
     */
    @Override
    @Transactional
    public TicketTypeResponse createTicketType(TicketTypeCreateRequest request) {
        if (request == null)
            return null;
        TicketType entity = mapToEntity(request);
        TicketType saved = ticketTypeRepository.save(entity);
        return mapToResponse(saved);
    }

    /**
     * Đồng bộ các loại vé của sự kiện (hoặc của một suất diễn) theo danh sách ban tổ chức gửi lên: cập nhật dòng khớp theo id hoặc
     * tên, tạo dòng mới nếu được phép, và xóa các dòng không còn được liệt kê (ẩn đi nếu đã có bán hoặc giữ chỗ).
     *
     * @param eventId mã sự kiện
     * @param showtimeId mã suất diễn; null hoặc rỗng nghĩa là đồng bộ cấp sự kiện
     * @param requests danh sách loại vé mới
     * @return các loại vé sau đồng bộ, không trùng tên
     * @throws ResponseStatusException 409 {@code TICKET_NAME_CONFLICT} khi đổi tên trùng loại vé khác
     */
    @Override
    @Transactional
    public List<TicketTypeResponse> syncTicketTypesForEvent(String eventId, String showtimeId, List<TicketTypeCreateRequest> requests) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return List.of();
        }
        boolean showtimeScoped = showtimeId != null && !showtimeId.isBlank();

        List<TicketType> all = ticketTypeRepository.findByEventId(eventId);
        boolean eventHasShowtimes = all.stream().anyMatch(TicketServiceImpl::hasShowtime);
        // phạm vi = các dòng lần gọi này được phép đụng tới. Đồng bộ cấp sự kiện không bao giờ sửa hay tạo dòng gốc khi
        // sự kiện đã có dòng theo suất diễn; khi đồng bộ theo suất diễn thì chỉ cập nhật các dòng của suất khớp theo id hoặc tên.
        List<TicketType> scope = new ArrayList<>();
        for (TicketType tt : all) {
            if (showtimeScoped ? showtimeId.equals(tt.getShowtimeId()) : (eventHasShowtimes == hasShowtime(tt))) {
                scope.add(tt);
            }
        }
        Map<String, TicketType> byId = new HashMap<>();
        Map<String, List<TicketType>> byName = new HashMap<>();
        for (TicketType tt : scope) {
            indexRow(byId, byName, tt);
        }

        Map<String, TicketType> processed = new LinkedHashMap<>();
        boolean createAllowed = showtimeScoped || !eventHasShowtimes;

        if (requests != null) {
            for (TicketTypeCreateRequest req : requests) {
                if (req == null) continue;
                req.setEventId(eventId);
                if (showtimeScoped) req.setShowtimeId(showtimeId);
                String requestedId = req.getId();
                String reqName = req.getName() != null ? req.getName().trim() : "";

                List<TicketType> targets = new ArrayList<>();
                // đổi tên chỉ nhận ra qua id; nếu không thì khớp theo tên là khóa tự nhiên
                if (requestedId != null && !requestedId.isBlank() && !requestedId.startsWith("tier_") && byId.containsKey(requestedId)) {
                    TicketType direct = byId.get(requestedId);
                    String newKey = normalizeName(reqName);
                    if (!reqName.isEmpty() && !normalizeName(direct.getName()).equals(newKey)) {
                        for (TicketType other : byName.getOrDefault(newKey, List.of())) {
                            if (!other.getId().equals(direct.getId()) && Objects.equals(other.getShowtimeId(), direct.getShowtimeId())) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NAME_CONFLICT");
                            }
                        }
                    }
                    targets.add(direct);
                } else if (!reqName.isEmpty()) {
                    targets.addAll(byName.getOrDefault(normalizeName(reqName), List.of()));
                }

                if (!targets.isEmpty()) {
                    for (TicketType existing : targets) {
                        applyToExisting(existing, req, reqName);
                        processed.put(existing.getId(), existing);
                    }
                } else if (createAllowed) {
                    req.setId(null);
                    TicketType created = ticketTypeRepository.save(mapToEntity(req));
                    processed.put(created.getId(), created);
                    indexRow(byId, byName, created);
                }
            }
        }

        // các dòng trong phạm vi mà yêu cầu không còn liệt kê sẽ bị xóa, hoặc bị ẩn nếu đã có bán hay giữ chỗ tham chiếu tới
        for (TicketType tt : scope) {
            if (processed.containsKey(tt.getId())) continue;
            boolean inUse = (tt.getReservedQuantity() != null && tt.getReservedQuantity() > 0)
                    || (tt.getInitialQuantity() != null && tt.getTotalQuantity() != null && tt.getInitialQuantity() > tt.getTotalQuantity())
                    || ticketRepository.existsByTicketType_Id(tt.getId());
            if (inUse) {
                TicketType fresh = ticketTypeRepository.findById(tt.getId()).orElse(null);
                if (fresh != null) {
                    fresh.setTicketTypeStatus(TicketTypeStatus.HIDDEN);
                    ticketTypeRepository.save(fresh);
                }
            } else {
                ticketTypeRepository.deleteById(tt.getId());
            }
        }

        List<TicketTypeResponse> responses = new ArrayList<>();
        Set<String> namesInResponse = new HashSet<>();
        for (String id : processed.keySet()) {
            TicketType fresh = ticketTypeRepository.findById(id).orElse(null);
            if (fresh != null && namesInResponse.add(normalizeName(fresh.getName()))) {
                responses.add(mapToResponse(fresh));
            }
        }
        return responses;
    }

    /**
     * Cập nhật nguyên tử một dòng loại vé đã có bằng một câu UPDATE; 0 dòng nghĩa là số lượng phát hành mới thấp hơn đã bán cộng đã giữ.
     *
     * @throws ResponseStatusException 400 khi số lượng âm, 409 khi loại vé đã bị đổi
     * @throws QuantityBelowSoldAndHeldException khi số lượng mới thấp hơn đã bán cộng đã giữ
     */
    private void applyToExisting(TicketType existing, TicketTypeCreateRequest req, String reqName) {
        String name = reqName.isEmpty() ? existing.getName() : reqName;
        Double price = req.getPrice() != null ? req.getPrice() : existing.getPrice();
        Boolean isFree = req.getIsFree() != null ? req.getIsFree() : (price != null && price == 0.0);
        Integer min = req.getMinPerOrder() != null ? req.getMinPerOrder() : (existing.getMinPerOrder() != null ? existing.getMinPerOrder() : 1);
        Integer max = req.getMaxPerOrder() != null ? req.getMaxPerOrder() : (existing.getMaxPerOrder() != null ? existing.getMaxPerOrder() : 5);
        String description = req.getDescription() != null ? req.getDescription() : existing.getDescription();
        Integer newInitial = req.getInitialQuantity() != null ? req.getInitialQuantity() : req.getTotalQuantity();
        LocalDateTime now = LocalDateTime.now();

        if (newInitial == null) {
            ticketTypeRepository.applyDetailsAtomic(existing.getId(), name, price, isFree, min, max, description, now);
            return;
        }
        if (newInitial < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        }
        if (existing.getInitialQuantity() == null) {
            ticketTypeRepository.backfillInitialQuantityAtomic(existing.getId()); // để các vé đã bán không bị quên ở bước dưới
        }
        int updated = ticketTypeRepository.applyIssuedQuantityAtomic(existing.getId(), newInitial, name, price, isFree, min, max, description, now);
        if (updated == 0) {
            TicketType current = ticketTypeRepository.findById(existing.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_TYPE_CHANGED"));
            Integer initial = current.getInitialQuantity();
            Integer total = current.getTotalQuantity();
            int sold = initial == null || total == null ? 0 : Math.max(0, initial - total);
            throw new QuantityBelowSoldAndHeldException(current.getId(), current.getName(), sold,
                    TicketQuantities.held(current.getReservedQuantity()));
        }
    }

    /** Loại vé có gắn suất diễn hay không (dòng theo suất, không phải dòng gốc). */
    private static boolean hasShowtime(TicketType tt) {
        return tt.getShowtimeId() != null && !tt.getShowtimeId().isBlank();
    }

    /** Chuẩn hóa tên để so khớp: bỏ khoảng trắng đầu cuối và viết thường; null thành chuỗi rỗng. */
    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    /** Thêm loại vé vào hai chỉ mục tra cứu theo id và theo tên đã chuẩn hóa. */
    private static void indexRow(Map<String, TicketType> byId, Map<String, List<TicketType>> byName, TicketType tt) {
        byId.put(tt.getId(), tt);
        byName.computeIfAbsent(normalizeName(tt.getName()), k -> new ArrayList<>()).add(tt);
    }

    /** Các dòng hiển thị cho khách: bỏ dòng bị ẩn; nếu sự kiện có suất diễn thì chỉ lấy dòng theo suất (không lấy dòng gốc không gắn suất), ngược lại lấy dòng gốc. */
    private List<TicketType> visibleRows(String eventId) {
        List<TicketType> list = ticketTypeRepository.findByEventId(eventId).stream()
                .filter(tt -> tt.getTicketTypeStatus() != TicketTypeStatus.HIDDEN)
                .toList();
        if (list.stream().anyMatch(TicketServiceImpl::hasShowtime)) {
            return list.stream().filter(TicketServiceImpl::hasShowtime).toList();
        }
        return list;
    }

    /**
     * Lấy các loại vé hiển thị của sự kiện.
     *
     * @param eventId mã sự kiện
     * @return danh sách loại vé, rỗng khi mã trống
     */
    @Override
    public List<TicketTypeResponse> getTicketTypesByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty())
            return List.of();
        return visibleRows(eventId).stream().map(this::mapToResponse).toList();
    }

    /**
     * Lấy các loại vé của sự kiện; bản cài đặt hiện tại dùng cùng quy tắc với {@code getTicketTypesByEventId} nên cũng bỏ loại vé đã ẩn.
     *
     * @param eventId mã sự kiện
     * @return danh sách loại vé, rỗng khi mã trống
     */
    @Override
    public List<TicketTypeResponse> getAllTicketTypesByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty())
            return List.of();
        return visibleRows(eventId).stream().map(this::mapToResponse).toList();
    }

    /**
     * Lấy một loại vé theo mã.
     *
     * @param id mã loại vé
     * @return loại vé, hoặc null khi mã trống hay không tồn tại
     */
    @Override
    public TicketTypeResponse getTicketTypeById(String id) {
        if (id == null || id.trim().isEmpty())
            return null;
        return ticketTypeRepository.findById(id)
                .map(this::mapToResponse)
                .orElse(null);
    }

    /**
     * Lấy các loại vé của một suất diễn, bỏ loại vé đã ẩn và gộp các loại trùng tên (giữ dòng đầu tiên).
     *
     * @param showtimeId mã suất diễn
     * @return danh sách loại vé, rỗng khi mã trống
     */
    @Override
    public List<TicketTypeResponse> getTicketTypesByShowtimeId(String showtimeId) {
        if (showtimeId == null || showtimeId.trim().isEmpty())
            return List.of();
        List<TicketType> list = ticketTypeRepository.findByShowtimeId(showtimeId);
        Map<String, TicketType> distinctMap = new LinkedHashMap<>();
        for (TicketType tt : list) {
            if (tt.getTicketTypeStatus() == TicketTypeStatus.HIDDEN) {
                continue;
            }
            String key = tt.getName() != null ? tt.getName().trim().toLowerCase() : tt.getId();
            distinctMap.putIfAbsent(key, tt);
        }
        return distinctMap.values().stream()
                .map(this::mapToResponse)
                .toList();
    }

    /**
     * Đọc thời điểm từ chuỗi, thử lần lượt các định dạng ISO local, thiếu giây, có offset và instant.
     *
     * @param str chuỗi thời gian
     * @return thời điểm theo giờ địa phương, hoặc null khi trống hay không đọc được
     */
    private LocalDateTime parseDateTime(String str) {
        if (str == null || str.trim().isEmpty())
            return null;
        String s = str.trim();
        if (s.contains(" ") && !s.contains("T")) {
            s = s.replace(" ", "T");
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s + ":00");
            } catch (Exception ex) {
                try {
                    return OffsetDateTime.parse(s).toLocalDateTime();
                } catch (Exception ex2) {
                    try {
                        return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDateTime();
                    } catch (Exception ex3) {
                        return null;
                    }
                }
            }
        }
    }

    /**
     * Đổi yêu cầu thành entity loại vé mới: bắt đầu với chưa bán gì (còn lại bằng ban đầu bằng số phát hành, mặc định 100),
     * id kiểu {@code tier_*} từ giao diện bị bỏ để DB tự sinh.
     */
    private TicketType mapToEntity(TicketTypeCreateRequest req) {

        String existingId = (req.getId() != null && !req.getId().startsWith("tier_") && !req.getId().isBlank()) ? req.getId() : null;
        // dòng mới bắt đầu với chưa bán gì: còn lại = ban đầu = số phát hành (dự phòng trường cũ, rồi mặc định 100)
        int issued = req.getInitialQuantity() != null ? req.getInitialQuantity()
                : (req.getTotalQuantity() != null ? req.getTotalQuantity() : 100);

        return TicketType.builder()
                .id(existingId)
                .eventId(req.getEventId())
                .showtimeId(req.getShowtimeId())
                .name(req.getName() != null ? req.getName() : "Vé tiêu chuẩn")
                .price(req.getPrice() != null ? req.getPrice() : 0.0)
                .isFree(req.getIsFree() != null ? req.getIsFree() : (req.getPrice() != null && req.getPrice() == 0.0))
                .totalQuantity(issued)
                .initialQuantity(issued)
                .minPerOrder(req.getMinPerOrder() != null ? req.getMinPerOrder() : 1)
                .maxPerOrder(req.getMaxPerOrder() != null ? req.getMaxPerOrder() : 10)
                .description(req.getDescription())
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
    }

    /** Đổi entity loại vé thành phản hồi, gồm các số còn lại, đang giữ, còn mua được và đã bán. */
    private TicketTypeResponse mapToResponse(TicketType entity) {
        if (entity == null)
            return null;
        return TicketTypeResponse.builder()
                .id(entity.getId())
                .eventId(entity.getEventId())
                .showtimeId(entity.getShowtimeId())
                .name(entity.getName())
                .price(entity.getPrice())
                .isFree(entity.getIsFree())
                .totalQuantity(entity.getTotalQuantity())
                .initialQuantity(entity.getInitialQuantity())
                .availableQuantity(TicketQuantities.available(entity.getTotalQuantity(), entity.getReservedQuantity()))
                .remainingQuantity(TicketQuantities.remaining(entity.getTotalQuantity()))
                .heldQuantity(TicketQuantities.held(entity.getReservedQuantity()))
                .soldQuantity(TicketQuantities.sold(entity.getInitialQuantity(), entity.getTotalQuantity()))
                .minPerOrder(entity.getMinPerOrder())
                .maxPerOrder(entity.getMaxPerOrder())
                .description(entity.getDescription())
                .status(entity.getTicketTypeStatus())
                .build();
    }

    /**
     * Lưu sơ đồ ghế của sự kiện ở trạng thái nháp.
     *
     * @param eventId mã sự kiện
     * @param request nội dung sơ đồ
     * @return sơ đồ đã lưu
     */
    @Override
    @Transactional
    public SeatLayoutResponse saveSeatLayoutDraft(String eventId, SeatLayoutRequest request) {
        return processSaveSeatLayout(eventId, request, SeatLayoutStatus.DRAFT);
    }

    /**
     * Lưu và xuất bản sơ đồ ghế của sự kiện.
     *
     * @param eventId mã sự kiện
     * @param request nội dung sơ đồ
     * @return sơ đồ đã xuất bản
     */
    @Override
    @Transactional
    public SeatLayoutResponse publishSeatLayout(String eventId, SeatLayoutRequest request) {
        return processSaveSeatLayout(eventId, request, SeatLayoutStatus.PUBLISHED);
    }

    /**
     * Lấy sơ đồ ghế cập nhật gần nhất của sự kiện kèm các khu ghế.
     *
     * @param eventId mã sự kiện
     * @return sơ đồ ghế, hoặc null khi mã trống hay chưa có sơ đồ
     */
    @Override
    @Transactional(readOnly = true)
    public SeatLayoutResponse getSeatLayoutByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return null;
        }
        List<SeatLayout> layouts = seatLayoutRepository.findByEventId(eventId);
        if (layouts.isEmpty()) {
            return null;
        }
        SeatLayout layout = layouts.stream()
                .max(Comparator.comparing(l -> l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getCreatedAt()))
                .orElse(layouts.get(0));

        List<SeatZone> zones = seatZoneRepository.findBySeatLayoutId(layout.getId());
        return mapLayoutToResponse(layout, zones);
    }

    /**
     * Lưu sơ đồ ghế với trạng thái cho trước. Dùng lại sơ đồ gần nhất của sự kiện (xóa các sơ đồ cũ thừa), cập nhật nội dung
     * và thay toàn bộ khu ghế theo yêu cầu.
     *
     * @throws IllegalArgumentException khi không xác định được mã sự kiện
     */
    private SeatLayoutResponse processSaveSeatLayout(String eventId, SeatLayoutRequest request, SeatLayoutStatus status) {
        String targetEventId = (eventId != null && !eventId.trim().isEmpty()) ? eventId : request.getEventId();
        if (targetEventId == null || targetEventId.trim().isEmpty()) {
            throw new IllegalArgumentException("eventId is required for SeatLayout");
        }

        List<SeatLayout> existingLayouts = seatLayoutRepository.findByEventId(targetEventId);
        SeatLayout layout;
        if (!existingLayouts.isEmpty()) {
            layout = existingLayouts.stream()
                    .max(Comparator.comparing(l -> l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getCreatedAt()))
                    .orElse(existingLayouts.get(0));

            for (SeatLayout old : existingLayouts) {
                if (!old.getId().equals(layout.getId())) {
                    try {
                        seatLayoutRepository.delete(old);
                    } catch (Exception ignored) {}
                }
            }
        } else {
            layout = SeatLayout.builder()
                    .eventId(targetEventId)
                    .status(status)
                    .seatZones(new ArrayList<>())
                    .build();
        }

        layout.setStatus(status);
        if (request.getDraftJson() != null) layout.setDraftJson(request.getDraftJson());
        if (request.getSvgData() != null) layout.setSvgData(request.getSvgData());
        if (request.getImageUrl() != null) layout.setImageUrl(request.getImageUrl());

        if (request.getZones() != null) {
            if (layout.getSeatZones() == null) {
                layout.setSeatZones(new ArrayList<>());
            } else {
                layout.getSeatZones().clear();
            }

            for (SeatLayoutRequest.ZoneDto zDto : request.getZones()) {
                SeatZone zone = SeatZone.builder()
                        .seatLayout(layout)
                        .name(zDto.getName() != null ? zDto.getName() : "Khu vực mới")
                        .ticketTypeId(zDto.getTicketTypeId())
                        .shapeType(zDto.getShapeType() != null ? zDto.getShapeType() : "RECTANGLE")
                        .colorCode(zDto.getColorCode())
                        .fillColor(zDto.getFillColor())
                        .strokeColor(zDto.getStrokeColor())
                        .curvature(zDto.getCurvature())
                        .x(zDto.getX())
                        .y(zDto.getY())
                        .width(zDto.getWidth())
                        .height(zDto.getHeight())
                        .rotation(zDto.getRotation())
                        .points(zDto.getPoints())
                        .capacity(zDto.getCapacity())
                        .build();
                layout.getSeatZones().add(zone);
            }
        }

        SeatLayout savedLayout = seatLayoutRepository.save(layout);
        return mapLayoutToResponse(savedLayout, savedLayout.getSeatZones());
    }

    /** Đổi sơ đồ ghế và các khu ghế thành phản hồi; không truyền khu ghế thì dùng khu ghế của sơ đồ. */
    private SeatLayoutResponse mapLayoutToResponse(SeatLayout layout, List<SeatZone> zones) {
        if (layout == null) return null;
        List<SeatLayoutResponse.ZoneResponseDto> zoneDtos = new ArrayList<>();
        List<SeatZone> seatZones = zones != null ? zones : (layout.getSeatZones() != null ? layout.getSeatZones() : List.of());
        for (SeatZone z : seatZones) {
            zoneDtos.add(SeatLayoutResponse.ZoneResponseDto.builder()
                    .id(z.getId())
                    .name(z.getName())
                    .ticketTypeId(z.getTicketTypeId())
                    .shapeType(z.getShapeType())
                    .colorCode(z.getColorCode())
                    .fillColor(z.getFillColor())
                    .strokeColor(z.getStrokeColor())
                    .curvature(z.getCurvature())
                    .x(z.getX())
                    .y(z.getY())
                    .width(z.getWidth())
                    .height(z.getHeight())
                    .rotation(z.getRotation())
                    .points(z.getPoints())
                    .capacity(z.getCapacity())
                    .build());
        }

        return SeatLayoutResponse.builder()
                .id(layout.getId())
                .eventId(layout.getEventId())
                .draftJson(layout.getDraftJson())
                .svgData(layout.getSvgData())
                .imageUrl(layout.getImageUrl())
                .status(layout.getStatus() != null ? layout.getStatus().name() : null)
                .zones(zoneDtos)
                .build();
    }

    /**
     * Lấy trạng thái ghế từ Redis: ghế đang giữ là {@code HOLDING}, ghế đã bán là {@code SOLD} (bán ghi đè giữ). Đọc cả tập
     * ghế lẫn từng khóa lẻ. Redis lỗi thì trả bản đồ đang có, không ném lỗi.
     *
     * @param eventId mã sự kiện
     * @param showtimeId mã suất diễn; null thì dùng khóa cấp sự kiện
     * @return bản đồ mã ghế sang trạng thái
     */
    @Override
    public Map<String, String> getSeatStatusMap(String eventId, String showtimeId) {
        Map<String, String> statusMap = new HashMap<>();
        if (eventId == null || eventId.isBlank()) {
            return statusMap;
        }

        try {
            String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + eventId;
            // đọc tập ghế đang giữ trong Redis
            Set<String> heldSeats = stringRedisTemplate.opsForSet().members(showtimePrefix + ":held_seats");
            if (heldSeats != null && !heldSeats.isEmpty()) {
                for (String seatId : heldSeats) {
                    statusMap.put(seatId, "HOLDING");
                }
            }

            // dự phòng cho các khóa seat:hold lẻ
            Set<String> holdKeys = stringRedisTemplate.keys("seat:hold:" + showtimePrefix + ":*");
            if (holdKeys != null) {
                for (String key : holdKeys) {
                    String seatId = key.substring(("seat:hold:" + showtimePrefix + ":").length());
                    statusMap.put(seatId, "HOLDING");
                }
            }

            // đọc tập ghế đã bán trong Redis
            Set<String> soldSeats = stringRedisTemplate.opsForSet().members(showtimePrefix + ":sold_seats");
            if (soldSeats != null && !soldSeats.isEmpty()) {
                for (String seatId : soldSeats) {
                    statusMap.put(seatId, "SOLD");
                }
            }

            // dự phòng cho các khóa seat:sold lẻ
            Set<String> soldKeys = stringRedisTemplate.keys("seat:sold:" + showtimePrefix + ":*");
            if (soldKeys != null) {
                for (String key : soldKeys) {
                    String seatId = key.substring(("seat:sold:" + showtimePrefix + ":").length());
                    statusMap.put(seatId, "SOLD");
                }
            }
        } catch (Exception e) {
            // Redis lỗi thì bỏ qua, trả những gì đã đọc được
        }

        return statusMap;
    }

    /**
     * Lấy vé của người dùng, mới phát hành trước.
     *
     * @param userId mã người dùng
     * @param pageable thông tin phân trang
     * @return trang vé, rỗng khi mã người dùng trống
     */
    @Override
    @Transactional(readOnly = true)
    public Page<OwnedTicketResponse> getMyTickets(String userId, Pageable pageable) {
        if (userId == null || userId.isBlank()) return Page.empty();
        return ticketRepository.findByOwnerIdOrderByIssuedAtDesc(userId, pageable)
                .map(OwnedTicketResponse::from);
    }

    /**
     * Lấy chi tiết một vé của người dùng.
     *
     * @param ticketId mã vé
     * @param userId mã người dùng, phải là chủ vé
     * @return thông tin vé
     * @throws RuntimeException khi không có vé hoặc người dùng không phải chủ vé
     */
    @Override
    @Transactional(readOnly = true)
    public OwnedTicketResponse getMyTicket(String ticketId, String userId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        if (!userId.equals(ticket.getOwnerId())) {
            throw new RuntimeException("Unauthorized");
        }
        return OwnedTicketResponse.from(ticket);
    }
}