package org.qommons.data.types;

import org.qommons.StringUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;

public class EntityTypeSet {
	private final BetterSortedSet<EntityType> theEntityTypes;
	private final BetterSortedSet<EnumType> theEnumTypes;

	public EntityTypeSet(BetterSortedSet<EntityType> entityTypes, BetterSortedSet<EnumType> enumTypes) {
		theEntityTypes = entityTypes;
		theEnumTypes = enumTypes;
	}

	public BetterSortedSet<EntityType> getEntityTypes() {
		return theEntityTypes;
	}

	public EntityType getEntityType(String name) {
		return theEntityTypes.searchValue(t -> StringUtils.compareNumberTolerant(name, t.getName(), true, true),
			SortedSearchFilter.OnlyMatch);
	}

	public BetterSortedSet<EnumType> getEnumTypes() {
		return theEnumTypes;
	}

	public EnumType getEnumType(String name) {
		return theEnumTypes.searchValue(e -> StringUtils.compareNumberTolerant(name, e.getName(), true, true),
			SortedSearchFilter.OnlyMatch);
	}
}
