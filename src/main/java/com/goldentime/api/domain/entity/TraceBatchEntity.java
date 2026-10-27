package com.goldentime.api.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "trace_batches")
public class TraceBatchEntity {

    @Id
    @Column(name = "batch_code")
    private String batchCode;

    @Column(name = "fruit_id")
    private Long fruitId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    private String origin;

    private String supplier;

    private boolean certified;

    @Column(name = "intake_date")
    private LocalDate intakeDate;

    @Column(columnDefinition = "TEXT")
    private String certificates;

    @Column(name = "storage_temperature")
    private String storageTemperature;

    @Column(name = "storage_humidity")
    private String storageHumidity;

    @Column(name = "public_note", columnDefinition = "TEXT")
    private String publicNote;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "farm_id")
    private Long farmId;

    @Column(name = "quantity_kg", precision = 12, scale = 2)
    private BigDecimal quantityKg;

    @Column(name = "harvest_date")
    private LocalDate harvestDate;

    @Column(name = "packaging_date")
    private LocalDate packagingDate;

    @Column(name = "quality_checked_at")
    private LocalDate qualityCheckedAt;

    @Column(name = "quality_result", length = 64)
    private String qualityResult;

    @Column(name = "quality_score")
    private Integer qualityScore;

    @Column(name = "quality_note", columnDefinition = "TEXT")
    private String qualityNote;

    @Column(name = "transport_date")
    private LocalDate transportDate;

    @Column(name = "transport_info", columnDefinition = "TEXT")
    private String transportInfo;

    @Column(name = "batch_status", nullable = false, length = 24)
    private String batchStatus = "IN_PROGRESS";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getBatchCode() {
        return batchCode;
    }

    public void setBatchCode(String batchCode) {
        this.batchCode = batchCode;
    }

    public Long getFruitId() {
        return fruitId;
    }

    public void setFruitId(Long fruitId) {
        this.fruitId = fruitId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getSupplier() {
        return supplier;
    }

    public void setSupplier(String supplier) {
        this.supplier = supplier;
    }

    public boolean isCertified() {
        return certified;
    }

    public void setCertified(boolean certified) {
        this.certified = certified;
    }

    public LocalDate getIntakeDate() { return intakeDate; }
    public void setIntakeDate(LocalDate intakeDate) { this.intakeDate = intakeDate; }
    public String getCertificates() { return certificates; }
    public void setCertificates(String certificates) { this.certificates = certificates; }
    public String getStorageTemperature() { return storageTemperature; }
    public void setStorageTemperature(String storageTemperature) { this.storageTemperature = storageTemperature; }
    public String getStorageHumidity() { return storageHumidity; }
    public void setStorageHumidity(String storageHumidity) { this.storageHumidity = storageHumidity; }
    public String getPublicNote() { return publicNote; }
    public void setPublicNote(String publicNote) { this.publicNote = publicNote; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }
    public Long getFarmId() { return farmId; }
    public void setFarmId(Long farmId) { this.farmId = farmId; }
    public BigDecimal getQuantityKg() { return quantityKg; }
    public void setQuantityKg(BigDecimal quantityKg) { this.quantityKg = quantityKg; }
    public LocalDate getHarvestDate() { return harvestDate; }
    public void setHarvestDate(LocalDate harvestDate) { this.harvestDate = harvestDate; }
    public LocalDate getPackagingDate() { return packagingDate; }
    public void setPackagingDate(LocalDate packagingDate) { this.packagingDate = packagingDate; }
    public LocalDate getQualityCheckedAt() { return qualityCheckedAt; }
    public void setQualityCheckedAt(LocalDate qualityCheckedAt) { this.qualityCheckedAt = qualityCheckedAt; }
    public String getQualityResult() { return qualityResult; }
    public void setQualityResult(String qualityResult) { this.qualityResult = qualityResult; }
    public Integer getQualityScore() { return qualityScore; }
    public void setQualityScore(Integer qualityScore) { this.qualityScore = qualityScore; }
    public String getQualityNote() { return qualityNote; }
    public void setQualityNote(String qualityNote) { this.qualityNote = qualityNote; }
    public LocalDate getTransportDate() { return transportDate; }
    public void setTransportDate(LocalDate transportDate) { this.transportDate = transportDate; }
    public String getTransportInfo() { return transportInfo; }
    public void setTransportInfo(String transportInfo) { this.transportInfo = transportInfo; }
    public String getBatchStatus() { return batchStatus; }
    public void setBatchStatus(String batchStatus) { this.batchStatus = batchStatus; }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
