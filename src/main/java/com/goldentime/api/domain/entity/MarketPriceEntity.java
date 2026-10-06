package com.goldentime.api.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "market_prices")
public class MarketPriceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fruit_id", nullable = false)
    private Long fruitId;

    @Column(name = "retailer_id", nullable = false)
    private Long retailerId;

    @Column(name = "price_per_kg", nullable = false)
    private Long pricePerKg;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFruitId() {
        return fruitId;
    }

    public void setFruitId(Long fruitId) {
        this.fruitId = fruitId;
    }

    public Long getRetailerId() {
        return retailerId;
    }

    public void setRetailerId(Long retailerId) {
        this.retailerId = retailerId;
    }

    public Long getPricePerKg() {
        return pricePerKg;
    }

    public void setPricePerKg(Long pricePerKg) {
        this.pricePerKg = pricePerKg;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
