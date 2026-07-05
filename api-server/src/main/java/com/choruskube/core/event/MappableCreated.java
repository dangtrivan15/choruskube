package com.choruskube.core.event;

import java.util.UUID;

/** Published synchronously right after a mappable entity is persisted (same transaction). */
public record MappableCreated(String resourceType, UUID resourceId, ParentRef parent) {
    public record ParentRef(String parentType, UUID parentId) {}

    public static MappableCreated of(String resourceType, UUID resourceId) {
        return new MappableCreated(resourceType, resourceId, null);
    }

    public static MappableCreated withParent(String resourceType, UUID resourceId, String parentType, UUID parentId) {
        return new MappableCreated(resourceType, resourceId, new ParentRef(parentType, parentId));
    }
}
