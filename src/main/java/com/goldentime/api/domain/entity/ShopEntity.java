package com.goldentime.api.domain.entity;

import com.goldentime.api.domain.enums.ShopStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shops")
public class ShopEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "rating_avg", nullable = false)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ShopStatus status = ShopStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "popular_fruit")
    private String popularFruit;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public BigDecimal getRatingAvg() {
        return ratingAvg;
    }

    public void setRatingAvg(BigDecimal ratingAvg) {
        this.ratingAvg = ratingAvg;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(int reviewCount) {
        this.reviewCount = reviewCount;
    }

    public ShopStatus getStatus() {
        return status;
    }

    public void setStatus(ShopStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPopularFruit() {
        return popularFruit;
    }

    public void setPopularFruit(String popularFruit) {
        this.popularFruit = popularFruit;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
