package org.qommons.data.values;

import org.qommons.collect.DequeList;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;

public interface GenericEntity {
	EntityType getType();

	GenericEntitySet getEntitySet();

	<T> T get(EntityField<T> field);

	GenericEntity set(EntityField<?> field, Object value);

	default Object[] getId() {
		DequeList<? extends EntityField<?>> idFields = getType().getIdFields();
		Object[] id = new Object[idFields.size()];
		int i = 0;
		for (EntityField<?> field : idFields)
			id[i] = get(field);
		return id;
	}

	default Object get(String fieldName) {
		EntityField<?> field = getType().getField(fieldName);
		if (field == null)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return get(field);
	}

	default GenericEntity set(String fieldName, Object value) {
		EntityField<?> field = getType().getField(fieldName);
		if (field == null)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return set(field, value);
	}

	void delete();

	GenericEntity immutableCopy();
}
