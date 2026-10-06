package com.haloticket.event_service.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "homepage_banners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HomepageBanner {
    @Id
    private String id;

    private String imageUrl;
    private String title;
    private boolean active;
    private String sourceType;
    private String linkUrl;
}
