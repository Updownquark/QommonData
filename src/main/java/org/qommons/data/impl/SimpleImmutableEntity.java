package org.qommons.data.impl;

import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;

public class SimpleImmutableEntity extends AbstractGenericEntity {
	public SimpleImmutableEntity(EntityType type, GenericEntitySet entitySet, Object[] fieldValues) {
		super(type, entitySet, fieldValues);
	}

	@Override
	public GenericEntity set(EntityField<?> field, Object value) {
		throw new UnsupportedOperationException("This entity cannot be modified");
	}

	@Override
	public void delete() {
		throw new UnsupportedOperationException("This entity cannot be deleted");
	}

	@Override
	public GenericEntity immutableCopy() {
		return this;
	}
}
