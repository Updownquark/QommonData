package org.qommons.data.types;

import java.util.Set;

import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.DequeList;
import org.qommons.data.values.GenericEntity;

public interface EntityType extends Named, EntityReferent<GenericEntity> {
	EntityTypeSet getTypeSet();

	EntityType getSuperType();

	default EntityType getRootType() {
		EntityType type = this;
		while (true) {
			EntityType superType = type.getSuperType();
			if (superType == null)
				return type;
			else
				type = superType;
		}
	}

	@Override
	default boolean isInstance(Object value) {
		return value instanceof GenericEntity && isAssignableFrom(((GenericEntity) value).getType());
	}

	@Override
	default int compare(GenericEntity o1, GenericEntity o2) {
		if (o1 == null) {
			if (o2 == null)
				return 0;
			else
				return 1;
		} else if (o2 == null)
			return -1;
		for (EntityField<?> field : getIdFields()) {
			int comp = ((FieldType<Object>) field.getType()).compare(o1.get(field), o2.get(field));
			if (comp != 0)
				return comp;
		}
		return 0;
	}

	@Override
	default GenericEntity convert(Object value, FieldType<?> valueType) {
		return (GenericEntity) value;
	}

	@Override
	default boolean isAssignableFrom(FieldType<?> other) {
		if (getClass() != other.getClass())
			return false;
		EntityType et = (EntityType) other;
		while (et != null && et != this)
			et = et.getSuperType();
		return et != null;
	}

	BetterSortedSet<? extends EntityField<?>> getLocalFields();

	BetterSortedSet<? extends EntityField<?>> getFields();

	DequeList<? extends EntityField<?>> getIdFields();

	default EntityField<?> getField(String name) {
		return getFields().searchValue(f -> StringUtils.compareNumberTolerant(name, f.getName(), true, true), SortedSearchFilter.OnlyMatch);
	}

	int indexOf(EntityField<?> field);

	Set<? extends EntityType> getSubTypes();
}
