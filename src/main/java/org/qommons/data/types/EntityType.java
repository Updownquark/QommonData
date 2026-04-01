package org.qommons.data.types;

import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;

public class EntityType implements Named, FieldType {
	private final EntityType theSuperType;
	private final String theName;
	private final BetterSortedSet<EntityField> theFields;
	private final BetterSortedSet<EntityField> theIdFields;

	public EntityType(EntityType superType, String name, BetterSortedSet<EntityField> fields, BetterSortedSet<EntityField> idFields) {
		theSuperType = superType;
		theName = name;
		theFields = fields;
		theIdFields = idFields;
	}

	public EntityType getSuperType() {
		return theSuperType;
	}

	@Override
	public String getName() {
		return theName;
	}

	public BetterSortedSet<EntityField> getFields() {
		return theFields;
	}

	public BetterSortedSet<EntityField> getIdFields() {
		return theIdFields;
	}

	public EntityField getField(String name) {
		return theFields.searchValue(f -> StringUtils.compareNumberTolerant(name, f.getName(), true, true), SortedSearchFilter.OnlyMatch);
	}
}
