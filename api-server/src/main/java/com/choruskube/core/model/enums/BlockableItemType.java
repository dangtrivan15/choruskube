package com.choruskube.core.model.enums;

/**
 * The two work-item kinds that can participate in a {@code work_item_dependency} edge (Roadmap
 * Graph View, Part 2). Unlike {@link WorkItemStatus}/{@link ReviewerType}/{@link ExecutorType},
 * this does not map to a native Postgres enum type — {@code blocking_item_type}/{@code
 * blocked_item_type} are plain {@code VARCHAR(16)} columns with a CHECK constraint, so the JPA
 * mapping uses plain {@code @Enumerated(EnumType.STRING)} with no {@code @JdbcTypeCode}.
 */
public enum BlockableItemType {
    story,
    task
}
