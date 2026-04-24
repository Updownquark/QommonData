package org.qommons.data.types;

import java.util.Set;

import org.qommons.Named;

public interface EntityField<F> extends Named {
	EntityType getOwner();

	@Override
	String getName();

	FieldType<F> getType();

	boolean isId();

	FieldMapping<F, ?, ?> getMapping();

	FieldMapping<?, ?, ?> getMappingReference();

	FieldMapping<?, ?, ?> getIndexReference();

	Set<FieldMapping<?, ?, ?>> getAncillaryMappingReferences();
}
