package org.qommons.data.types;

import org.qommons.Named;

public class EntityField implements Named {
	private final EntityType theOwner;
	private final String theName;
	private final FieldType theType;

	public EntityField(EntityType owner, String name, FieldType type) {
		theOwner = owner;
		theName = name;
		theType = type;
	}

	public EntityType getOwner() {
		return theOwner;
	}

	@Override
	public String getName() {
		return theName;
	}

	public FieldType getType() {
		return theType;
	}

	@Override
	public String toString() {
		return theOwner + "." + getName() + " (" + theType + ")";
	}
}
