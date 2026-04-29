package org.qommons.data.types.modifiable;

import java.util.Collections;
import java.util.Set;

import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterHashMultiMap;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.MappedBetterSortedSet;
import org.qommons.collect.MappedSet;
import org.qommons.data.migration.MigrationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.io.FilePosition;
import org.qommons.tree.BetterTreeSet;

public class ModifiableEnumType implements EnumType {
	private final ModifiableEntityTypeSet theTypeSet;
	private String theName;
	private final BetterSortedSet<ModifiableEnumValue> theValues;
	private final BetterMultiMap<ModifiableEntityType, ModifiableEntityField<EnumValue>> theReferences;
	private final Unmodifiable theUnmodifiable;

	public ModifiableEnumType(ModifiableEntityTypeSet typeSet, String name) {
		theTypeSet = typeSet;
		theName = name;
		theValues = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		theReferences = BetterHashMultiMap.create();
		theUnmodifiable = new Unmodifiable(this);
	}

	@Override
	public String getName() {
		return theName;
	}

	public ModifiableEnumType setName(String newName, FilePosition source) throws MigrationException {
		if (theTypeSet.getEnumType(newName) != null)
			throw new MigrationException("Another enum type named '" + newName + "' already exists", source);
		theTypeSet.renameEnum(this, newName, source);
		return this;
	}

	void doSetName(String newName) {
		theName = newName;
	}

	@Override
	public BetterSortedSet<ModifiableEnumValue> getValues() {
		return theValues;
	}

	@Override
	public ModifiableEnumValue getValue(String name) {
		return (ModifiableEnumValue) EnumType.super.getValue(name);
	}

	@Override
	public Set<ModifiableEntityType> getReferrers() {
		return Collections.unmodifiableSet(theReferences.keySet());
	}

	@Override
	public Set<ModifiableEntityField<EnumValue>> getReferences(EntityType type) {
		return Collections.unmodifiableSet((Set<ModifiableEntityField<EnumValue>>) theReferences.get((ModifiableEntityType) type));
	}

	public EnumType unmodifiableView() {
		return theUnmodifiable;
	}

	public ModifiableEnumValue addValue(String name, FilePosition source) throws MigrationException {
		ModifiableEnumValue newValue = new ModifiableEnumValue(this, name);
		if (!theValues.add(newValue))
			throw new MigrationException("A value named '" + name + "' already exists in enum '" + theName + "'", source);
		return newValue;
	}

	public void delete(FilePosition source) throws MigrationException {
		if (!theReferences.isEmpty()) {
			StringBuilder str = new StringBuilder("There are ").append(theReferences.valueSize())
				.append(" entity fields that reference enum ").append(theName);
			for (ModifiableEntityField<EnumValue> field : theReferences.values())
				str.append("\n\t").append(field);
			throw new MigrationException(str.toString(), source);
		}
		theTypeSet.removeEnum(this);
	}

	void renameValue(ModifiableEnumValue value, String newName, FilePosition source) throws MigrationException {
		if (getValue(newName) != null)
			throw new MigrationException("Another " + theName + " value named '" + newName + "' already exists", source);
		theValues.remove(value);
		value.doSetName(newName);
		theValues.add(value);
	}

	void removeValue(ModifiableEnumValue value) {
		theValues.remove(value);
	}

	void addReference(ModifiableEntityField<EnumValue> field) {
		theReferences.add(field.getOwner(), field);
	}

	void removeReference(ModifiableEntityField<EnumValue> field) {
		theReferences.remove(field.getOwner(), field);
	}

	public StringBuilder append(StringBuilder str, int indent) {
		str.append(theName);
		for (EnumValue value : theValues)
			StringUtils.indent(str.append('\n'), indent + 1).append(value.getName());
		return str;
	}

	@Override
	public String toString() {
		return theName;
	}

	private static class Unmodifiable implements EnumType {
		private final ModifiableEnumType theSource;
		private final BetterSortedSet<EnumValue> theValues;
		private final Set<EntityType> theReferrers;

		Unmodifiable(ModifiableEnumType source) {
			theSource = source;
			theValues = BetterCollections.unmodifiableSortedSet(
				new MappedBetterSortedSet<>(source.theValues, ModifiableEnumValue::unmodifiableView, null, Named.DISTINCT_NUMBER_TOLERANT));
			theReferrers = Collections
				.unmodifiableSet(new MappedSet<>(source.theReferences.keySet(), ModifiableEntityType::unmodifiableView,
					test -> theSource.theReferences.keySet().contains(((Unmodifiable) test).theSource)));
		}

		@Override
		public String getName() {
			return theSource.getName();
		}

		@Override
		public BetterSortedSet<? extends EnumValue> getValues() {
			return theValues;
		}

		@Override
		public Set<? extends EntityType> getReferrers() {
			return theReferrers;
		}

		@Override
		public Set<? extends EntityField<EnumValue>> getReferences(EntityType type) {
			Set<ModifiableEntityField<EnumValue>> refs = (Set<ModifiableEntityField<EnumValue>>) theSource.theReferences
				.get(((ModifiableEntityType.Unmodifiable) type).getSource());
			return new MappedSet<>(refs, ModifiableEntityField::unmodifiableView,
				test -> refs.contains(((ModifiableEntityField.Unmodifiable<?>) test).getSource()));
		}

		@Override
		public String toString() {
			return theSource.toString();
		}
	}
}
