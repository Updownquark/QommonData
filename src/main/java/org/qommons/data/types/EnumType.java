package org.qommons.data.types;

import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;

public class EnumType implements Named, FieldType {
	private final String theName;
	private final BetterSortedSet<EnumValue> theValues;

	public EnumType(String name, BetterSortedSet<EnumValue> values) {
		theName = name;
		theValues = values;
	}

	@Override
	public String getName() {
		return theName;
	}

	public BetterSortedSet<EnumValue> getValues() {
		return theValues;
	}

	public EnumValue getValue(String name) {
		return theValues.searchValue(v -> StringUtils.compareNumberTolerant(name, v.getName(), true, true), SortedSearchFilter.OnlyMatch);
	}
}
