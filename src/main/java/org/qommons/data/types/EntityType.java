package org.qommons.data.types;

import java.util.Set;
import java.util.function.Function;

import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterSet;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.DequeList;
import org.qommons.data.values.GenericEntity;

public interface EntityType extends Named, EntityReferent<GenericEntity> {
	EntityTypeSet getTypeSet();

	BetterSet<? extends EntityType> getSuperTypes();

	default EntityType getRootType() {
		EntityType t = this;
		EntityType sup = t.getSuperTypes().peekFirst();
		while (sup != null) {
			t = sup;
			sup = sup.getSuperTypes().peekFirst();
		}
		return t;
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
		BetterSet<EntityType> visited = null; // Used only for multi-inheritance
		EntityType et = (EntityType) other;
		while (et != null && et != this) {
			if (et.getSuperTypes().size() > 1) {
				if (et.getSuperTypes().contains(this))
					return true;
				for (EntityType sup : et.getSuperTypes()) {
					if (!sup.getSuperTypes().isEmpty()) {
						if (visited == null)
							visited = BetterHashSet.create();
						visited.addAll(sup.getSuperTypes());
					}
				}
				et = null;
				break;
			} else
				et = et.getSuperTypes().peekFirst();
		}
		if (et != null)
			return true;
		else if (visited != null) {
			for (CollectionElement<EntityType> sup = visited.getTerminalElement(true); sup != null; sup = sup.getAdjacent(true)) {
				if (sup.get() == this)
					return true;
				visited.addAll(sup.get().getSuperTypes());
			}
		}
		return false;
	}

	@Override
	default <FT extends FieldType<?>> FT containsTypeLike(Function<? super FieldType<?>, FT> test) {
		return test.apply(this);
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
