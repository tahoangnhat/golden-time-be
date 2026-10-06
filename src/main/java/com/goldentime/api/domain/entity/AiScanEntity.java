package com.goldentime.api.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "ai_scans")
public class AiScanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "fruit_id")
    private Long fruitId;

    @Column(name = "detected_name")
    private String detectedName;

    @Column(name = "quality_score")
    private Integer qualityScore;

    @Column(name = "freshness_score")
    private Integer freshnessScore;

    @Column(name = "ripeness_label")
    private String ripenessLabel;

    @Column(name = "sweetness_label")
    private String sweetnessLabel;

    @Column(name = "use_within")
    private String useWithin;

    @Column(name = "quality_warning")
    private String qualityWarning;

    private String suggestion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private Map<String, Object> rawResponse;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public Long getFruitId() {
        return fruitId;
    }

    public void setFruitId(Long fruitId) {
        this.fruitId = fruitId;
    }

    public String getDetectedName() {
        return detectedName;
    }

    public void setDetectedName(String detectedName) {
        this.detectedName = detectedName;
    }

    public Integer getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(Integer qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Integer getFreshnessScore() {
        return freshnessScore;
    }

    public void setFreshnessScore(Integer freshnessScore) {
        this.freshnessScore = freshnessScore;
    }

    public String getRipenessLabel() {
        return ripenessLabel;
    }

    public void setRipenessLabel(String ripenessLabel) {
        this.ripenessLabel = ripenessLabel;
    }

    public String getSweetnessLabel() {
        return sweetnessLabel;
    }

    public void setSweetnessLabel(String sweetnessLabel) {
        this.sweetnessLabel = sweetnessLabel;
    }

    public String getUseWithin() {
        return useWithin;
    }

    public void setUseWithin(String useWithin) {
        this.useWithin = useWithin;
    }

    public String getQualityWarning() {
        return qualityWarning;
    }

    public void setQualityWarning(String qualityWarning) {
        this.qualityWarning = qualityWarning;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public Map<String, Object> getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(Map<String, Object> rawResponse) {
        this.rawResponse = rawResponse;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
