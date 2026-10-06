package com.goldentime.api.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "fruit_nutrition")
public class FruitNutritionEntity {

    @Id
    @Column(name = "fruit_id")
    private Long fruitId;

    @Column(name = "calories_per_100g")
    private BigDecimal caloriesPer100g;

    @Column(name = "vitamin_c_mg")
    private BigDecimal vitaminCMg;

    @Column(name = "fiber_g")
    private BigDecimal fiberG;

    @Column(name = "sugar_g")
    private BigDecimal sugarG;

    @Column(name = "potassium_mg")
    private BigDecimal potassiumMg;

    @Column(name = "water_percent")
    private BigDecimal waterPercent;

    @Column(name = "health_benefits")
    private String healthBenefits;

    @Column(name = "serving_note")
    private String servingNote;

    @Column(name = "personalized_tip")
    private String personalizedTip;

    public Long getFruitId() {
        return fruitId;
    }

    public void setFruitId(Long fruitId) {
        this.fruitId = fruitId;
    }

    public BigDecimal getCaloriesPer100g() {
        return caloriesPer100g;
    }

    public void setCaloriesPer100g(BigDecimal caloriesPer100g) {
        this.caloriesPer100g = caloriesPer100g;
    }

    public BigDecimal getVitaminCMg() {
        return vitaminCMg;
    }

    public void setVitaminCMg(BigDecimal vitaminCMg) {
        this.vitaminCMg = vitaminCMg;
    }

    public BigDecimal getFiberG() {
        return fiberG;
    }

    public void setFiberG(BigDecimal fiberG) {
        this.fiberG = fiberG;
    }

    public BigDecimal getSugarG() {
        return sugarG;
    }

    public void setSugarG(BigDecimal sugarG) {
        this.sugarG = sugarG;
    }

    public BigDecimal getPotassiumMg() {
        return potassiumMg;
    }

    public void setPotassiumMg(BigDecimal potassiumMg) {
        this.potassiumMg = potassiumMg;
    }

    public BigDecimal getWaterPercent() {
        return waterPercent;
    }

    public void setWaterPercent(BigDecimal waterPercent) {
        this.waterPercent = waterPercent;
    }

    public String getHealthBenefits() {
        return healthBenefits;
    }

    public void setHealthBenefits(String healthBenefits) {
        this.healthBenefits = healthBenefits;
    }

    public String getServingNote() {
        return servingNote;
    }

    public void setServingNote(String servingNote) {
        this.servingNote = servingNote;
    }

    public String getPersonalizedTip() {
        return personalizedTip;
    }

    public void setPersonalizedTip(String personalizedTip) {
        this.personalizedTip = personalizedTip;
    }
}
