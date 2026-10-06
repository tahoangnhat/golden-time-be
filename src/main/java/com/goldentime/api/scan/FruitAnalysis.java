package com.goldentime.api.scan;

import com.fasterxml.jackson.annotation.JsonAlias;

public record FruitAnalysis(
        @JsonAlias({"fruit", "detectedName", "fruit_name"}) String fruitName,
        @JsonAlias({"quality_score"}) Integer qualityScore,
        @JsonAlias({"freshness_score"}) Integer freshnessScore,
        @JsonAlias({"ripeness", "ripeness_label"}) String ripenessLabel,
        @JsonAlias({"sweetness", "sweetness_label"}) String sweetnessLabel,
        @JsonAlias({"useWithin", "recommendedConsumptionTime"}) String useWithin,
        @JsonAlias({"warning", "quality_warning"}) String qualityWarning,
        String suggestion,
        Double confidence) {
}
