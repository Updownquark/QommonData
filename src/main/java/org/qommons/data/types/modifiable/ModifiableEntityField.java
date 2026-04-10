package org.qommons.data.types.modifiable;

import org.qommons.data.migration.MigrationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldType;
import org.qommons.io.FilePosition;

public class ModifiableEntityField<F> implements EntityField<F> {
	private final ModifiableEntityType theOwner;
	private String theName;
	private final FieldType<F> theType;
	private final boolean isId;
	private final Unmodifiable<F> theUnmodifiable;
	private int theRootIndex;

	ModifiableEntityField(ModifiableEntityType owner, String name, FieldType<F> type, boolean isId) {
		theOwner = owner;
		theName = name;
		theType = type;
		this.isId = isId;
		theUnmodifiable = new Unmodifiable<>(this);
	}

	@Override
	public ModifiableEntityType getOwner() {
		return theOwner;
	}

	@Override
	public String getName() {
		return theName;
	}

	public ModifiableEntityField setName(String newName, FilePosition source) throws MigrationException {
		theOwner.renameField(this, newName, source);
		return this;
	}

	void doSetName(String newName) {
		theName = newName;
	}

	@Override
	public FieldType<F> getType() {
		return theType;
	}

	@Override
	public boolean isId() {
		return isId;
	}

	public int getRootIndex() {
		return theRootIndex;
	}

	void setRootIndex(int rootIndex) {
		theRootIndex = rootIndex;
	}

	public EntityField<F> unmodifiableView() {
		return theUnmodifiable;
	}

	public void delete() {
		theOwner.removeField(this);
	}

	@Override
	public String toString() {
		String str = theOwner + "." + getName() + " (" + theType + ")";
		if (theRootIndex < 0)
			str += " (removed)";
		return str;
	}

	static class Unmodifiable<F> implements EntityField<F> {
		private final ModifiableEntityField<F> theSource;

		Unmodifiable(ModifiableEntityField<F> source) {
			theSource = source;
		}

		ModifiableEntityField<F> getSource() {
			return theSource;
		}

		@Override
		public EntityType getOwner() {
			return theSource.getOwner().unmodifiableView();
		}

		@Override
		public String getName() {
			return theSource.getName();
		}

		@Override
		public FieldType<F> getType() {
			FieldType<F> type = theSource.getType();
			if (type instanceof ModifiableEntityType)
				return (FieldType<F>) ((ModifiableEntityType) type).unmodifiableView();
			else if (type instanceof ModifiableEnumType)
				return (FieldType<F>) ((ModifiableEnumType) type).unmodifiableView();
			else
				return type;
		}

		@Override
		public boolean isId() {
			return theSource.isId();
		}

		@Override
		public String toString() {
			return theSource.toString();
		}
	}
}
