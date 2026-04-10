package org.qommons.data.types;

import org.qommons.Named;

public interface EntityField<F> extends Named {
	EntityType getOwner();

	@Override
	String getName();

	FieldType<F> getType();

	boolean isId();
}
