package com.chitthi.document.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> tags = new ArrayList<>();

    private Integer year;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    protected Document() {
    }

    public Document(String ownerId, String title, String language, List<String> tags, Integer year) {
        this.ownerId = ownerId;
        this.title = title;
        this.language = language;
        this.tags = tags != null ? tags : new ArrayList<>();
        this.year = year;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getLanguage() {
        return language;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    /**
     * Stamps or clears {@code completedAt} alongside the status itself, so
     * every call site - including the three that send a finished document
     * back to PROCESSING (a retry, an edit) - gets it right without having
     * to remember to. A document sent back to PROCESSING has its stamp
     * cleared so the next completion records a fresh, correct duration.
     */
    public void setStatus(DocumentStatus status) {
        this.status = status;
        this.completedAt = status.isTerminal() ? OffsetDateTime.now() : null;
    }

    public List<String> getTags() {
        return tags;
    }

    public Integer getYear() {
        return year;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }
}
