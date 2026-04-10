package org.qommons.data.types;

import org.qommons.StringUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;

public interface EntityTypeSet {
	BetterSortedSet<? extends EntityType> getEntityTypes();

	default EntityType getEntityType(String name) {
		return getEntityTypes().searchValue(t -> StringUtils.compareNumberTolerant(name, t.getName(), true, true),
			SortedSearchFilter.OnlyMatch);
	}

	BetterSortedSet<? extends EnumType> getEnumTypes();

	default EnumType getEnumType(String name) {
		return getEnumTypes().searchValue(e -> StringUtils.compareNumberTolerant(name, e.getName(), true, true),
			SortedSearchFilter.OnlyMatch);
	}
}
