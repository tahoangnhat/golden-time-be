package com.goldentime.api.domain.entity;

import com.goldentime.api.domain.enums.ProductStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shop_products")
public class ShopProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "fruit_id", nullable = false)
    private Long fruitId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "price_per_kg", nullable = false)
    private Long pricePerKg;

    @Column(name = "stock_kg", nullable = false)
    private BigDecimal stockKg = BigDecimal.ZERO;

    @Column(name = "ai_score")
    private Integer aiScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProductStatus status = ProductStatus.ON_SALE;

    @Column(name = "batch_code")
    private String batchCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public Long getFruitId() {
        return fruitId;
    }

    public void setFruitId(Long fruitId) {
        this.fruitId = fruitId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Long getPricePerKg() {
        return pricePerKg;
    }

    public void setPricePerKg(Long pricePerKg) {
        this.pricePerKg = pricePerKg;
    }

    public BigDecimal getStockKg() {
        return stockKg;
    }

    public void setStockKg(BigDecimal stockKg) {
        this.stockKg = stockKg;
    }

    public Integer getAiScore() {
        return aiScore;
    }

    public void setAiScore(Integer aiScore) {
        this.aiScore = aiScore;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
    }

    public String getBatchCode() {
        return batchCode;
    }

    public void setBatchCode(String batchCode) {
        this.batchCode = batchCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
