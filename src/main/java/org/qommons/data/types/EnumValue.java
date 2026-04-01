package org.qommons.data.types;

import org.qommons.Named;

public class EnumValue implements Named {
	private final EnumType theType;
	private final String theName;

	public EnumValue(EnumType type, String name) {
		theType = type;
		theName = name;
	}

	public EnumType getType() {
		return theType;
	}

	@Override
	public String getName() {
		return theName;
	}

	@Override
	public String toString() {
		return theType + "." + theName;
	}
}
