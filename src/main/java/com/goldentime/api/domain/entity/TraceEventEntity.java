package com.goldentime.api.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "trace_events")
public class TraceEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_code", nullable = false)
    private String batchCode;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false)
    private String title;

    @Column(name = "event_date")
    private String eventDate;

    private String location;

    @Column(name = "icon_key")
    private String iconKey;

    private boolean completed = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchCode() {
        return batchCode;
    }

    public void setBatchCode(String batchCode) {
        this.batchCode = batchCode;
    }

    public int getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(int stepOrder) {
        this.stepOrder = stepOrder;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getEventDate() {
        return eventDate;
    }

    public void setEventDate(String eventDate) {
        this.eventDate = eventDate;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getIconKey() {
        return iconKey;
    }

    public void setIconKey(String iconKey) {
        this.iconKey = iconKey;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }
}
