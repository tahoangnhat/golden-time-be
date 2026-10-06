package com.goldentime.api.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
