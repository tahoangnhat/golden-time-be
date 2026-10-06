package com.goldentime.api.domain.entity;

import com.goldentime.api.domain.enums.UpdateType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "app_updates")
public class AppUpdateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String version;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "update_type", nullable = false, length = 32)
    private UpdateType updateType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String changes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(LocalDate releaseDate) {
        this.releaseDate = releaseDate;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UpdateType getUpdateType() {
        return updateType;
    }

    public void setUpdateType(UpdateType updateType) {
        this.updateType = updateType;
    }

    public String getChanges() {
        return changes;
    }

    public void setChanges(String changes) {
        this.changes = changes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
