package org.qommons.data.types.modifiable;

import org.qommons.data.migration.MigrationException;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.io.FilePosition;

public class ModifiableEnumValue implements EnumValue {
	private final ModifiableEnumType theType;
	private String theName;
	private final Unmodifiable theUnmodifiable;

	public ModifiableEnumValue(ModifiableEnumType type, String name) {
		theType = type;
		theName = name;
		theUnmodifiable = new Unmodifiable(this);
	}

	@Override
	public ModifiableEnumType getType() {
		return theType;
	}

	@Override
	public String getName() {
		return theName;
	}

	public ModifiableEnumValue setName(String name, FilePosition source) throws MigrationException {
		theType.renameValue(this, name, source);
		return this;
	}

	void doSetName(String name) {
		theName = name;
	}

	public EnumValue unmodifiableView() {
		return theUnmodifiable;
	}

	public void delete() {
		theType.removeValue(this);
	}

	@Override
	public String toString() {
		return theType + "." + theName;
	}

	static class Unmodifiable implements EnumValue {
		private final ModifiableEnumValue theSource;

		Unmodifiable(ModifiableEnumValue source) {
			theSource = source;
		}

		ModifiableEnumValue getSource() {
			return theSource;
		}

		@Override
		public String getName() {
			return theSource.getName();
		}

		@Override
		public EnumType getType() {
			return theSource.getType().unmodifiableView();
		}

		@Override
		public String toString() {
			return theSource.toString();
		}
	}
}
