package org.qommons.data.values;

import org.qommons.collect.DequeList;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;

public interface GenericEntity {
	EntityType getType();

	GenericEntitySet getEntitySet();

	<T> T get(EntityField<T> field);

	String isEnabled(EntityField<?> field);

	String isAcceptable(EntityField<?> field, Object value);

	GenericEntity set(EntityField<?> field, Object value);

	default Object[] getId() {
		DequeList<? extends EntityField<?>> idFields = getType().getIdFields();
		Object[] id = new Object[idFields.size()];
		int i = 0;
		for (EntityField<?> field : idFields)
			id[i++] = get(field);
		return id;
	}

	default int compareToId(Object[] id) {
		int i = 0;
		for (EntityField<?> field : getType().getIdFields()) {
			int comp = ((EntityField<Object>) field).getType().compare(get(field), id[i]);
			if (comp != 0)
				return comp;
			i++;
		}
		return 0;
	}

	default Object get(String fieldName) {
		EntityField<?> field = getType().getField(fieldName);
		if (field == null)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return get(field);
	}

	default String isEnabled(String fieldName) {
		EntityField<?> field = getType().getField(fieldName);
		if (field == null)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return isEnabled(field);
	}

	default String isAcceptable(String fieldName, Object value) {
		EntityField<?> field = getType().getField(fieldName);
		if (field == null)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return isAcceptable(field, value);
	}

	default GenericEntity set(String fieldName, Object value) {
		EntityField<?> field = getType().getField(fieldName);
		if (field == null)
			throw new IllegalArgumentException("No such field " + getType() + "." + fieldName);
		return set(field, value);
	}

	String canDelete();

	void delete();
}
