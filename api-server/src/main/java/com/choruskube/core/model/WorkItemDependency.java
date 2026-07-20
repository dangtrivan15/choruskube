package com.choruskube.core.model;

import com.choruskube.core.model.enums.BlockableItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A "blocking" dependency edge between two Stories/Tasks (Roadmap Graph View, Part 2). Rows are
 * immutable once created (create/delete only, no update), so — unlike {@link Epic}/{@link
 * Story}/{@link Task} — this does not extend {@link BaseEntity}: the table has a {@code
 * created_at} column but no {@code updated_at} (mirrors {@link ExecutionLog}).
 */
@Entity
@Table(name = "work_item_dependency")
public class WorkItemDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "blocking_item_type", nullable = false)
    private BlockableItemType blockingItemType;

    @Column(name = "blocking_item_id", nullable = false)
    private UUID blockingItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "blocked_item_type", nullable = false)
    private BlockableItemType blockedItemType;

    @Column(name = "blocked_item_id", nullable = false)
    private UUID blockedItemId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public BlockableItemType getBlockingItemType() {
        return blockingItemType;
    }

    public void setBlockingItemType(BlockableItemType blockingItemType) {
        this.blockingItemType = blockingItemType;
    }

    public UUID getBlockingItemId() {
        return blockingItemId;
    }

    public void setBlockingItemId(UUID blockingItemId) {
        this.blockingItemId = blockingItemId;
    }

    public BlockableItemType getBlockedItemType() {
        return blockedItemType;
    }

    public void setBlockedItemType(BlockableItemType blockedItemType) {
        this.blockedItemType = blockedItemType;
    }

    public UUID getBlockedItemId() {
        return blockedItemId;
    }

    public void setBlockedItemId(UUID blockedItemId) {
        this.blockedItemId = blockedItemId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
