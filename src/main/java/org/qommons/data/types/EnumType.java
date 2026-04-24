package org.qommons.data.types;

import java.util.function.Function;

import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;

public interface EnumType extends Named, EntityReferent<EnumValue> {
	@Override
	default int compare(EnumValue o1, EnumValue o2) {
		if (o1 == null) {
			if (o2 == null)
				return 0;
			else
				return 1;
		} else if (o2 == null)
			return -1;
		return StringUtils.compareNumberTolerant(o1.getName(), o2.getName(), true, true);
	}

	@Override
	default boolean isInstance(Object value) {
		return value instanceof EnumValue && ((EnumValue) value).getType() == this;
	}

	@Override
	default EnumValue convert(Object value, FieldType<?> valueType) {
		return (EnumValue) value;
	}

	@Override
	default boolean isAssignableFrom(FieldType<?> other) {
		return other == this;
	}

	@Override
	default <FT extends FieldType<?>> FT containsTypeLike(Function<? super FieldType<?>, FT> test) {
		return test.apply(this);
	}

	BetterSortedSet<? extends EnumValue> getValues();

	default EnumValue getValue(String name) {
		return getValues().searchValue(v -> StringUtils.compareNumberTolerant(name, v.getName(), true, true), SortedSearchFilter.OnlyMatch);
	}
}
