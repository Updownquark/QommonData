package org.qommons.data.types.modifiable;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.io.LocatedPositionedContent;

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

	public ModifiableEnumValue setName(LocatedPositionedContent name) throws QonfigInterpretationException {
		theType.renameValue(this, name);
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
