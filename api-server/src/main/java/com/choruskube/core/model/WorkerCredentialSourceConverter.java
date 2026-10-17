package com.choruskube.core.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link WorkerCredentialSource} as its wire string, not the enum name, in the database too. */
@Converter(autoApply = false)
public class WorkerCredentialSourceConverter implements AttributeConverter<WorkerCredentialSource, String> {

    @Override
    public String convertToDatabaseColumn(WorkerCredentialSource attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public WorkerCredentialSource convertToEntityAttribute(String dbData) {
        return dbData == null ? null : WorkerCredentialSource.fromWire(dbData);
    }
}
