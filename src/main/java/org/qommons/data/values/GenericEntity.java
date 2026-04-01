package org.qommons.data.values;

import org.qommons.data.types.EntityType;

public interface GenericEntity {
	EntityType getType();

	GenericEntitySet getEntitySet();

	Object get(int fieldIndex);

	default Object get(String fieldName) {
		int index = getType().getFields().indexOf(fieldName);
		if (index < 0)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return get(index);
	}

	GenericEntity set(int fieldIndex, Object value);

	default GenericEntity set(String fieldName, Object value) {
		int index = getType().getFields().indexOf(fieldName);
		if (index < 0)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return set(index, value);
	}
}
