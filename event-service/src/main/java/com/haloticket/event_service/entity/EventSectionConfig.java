package com.haloticket.event_service.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Thực thể cấu hình một mục sự kiện trên trang chủ (bảng {@code event_section_configs}): tiêu đề, loại, số lượng hiển thị và nội dung quảng cáo đi kèm. */
@Entity
@Table(name = "event_section_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventSectionConfig {
    @Id
    private String id;

    private String title;
    private String type;
    
    @jakarta.persistence.Column(name = "section_limit")
    private int limit;

    private String adsImageUrl;
    private String adsSubtitle;
    private String adsDescription;
    private String adsButtonText;
    private String adsButtonUrl;
    
    @jakarta.persistence.Column(name = "artist_display_type")
    private String artistDisplayType;
}
