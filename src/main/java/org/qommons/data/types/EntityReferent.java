package org.qommons.data.types;

import java.util.Set;

public interface EntityReferent<T> extends FieldType<T> {
	Set<? extends EntityType> getReferrers();

	Set<? extends EntityField<T>> getReferences(EntityType type);
}
