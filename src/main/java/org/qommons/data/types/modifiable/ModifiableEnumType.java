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
import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.io.LocatedPositionedContent;
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

	public ModifiableEnumType setName(LocatedPositionedContent newName) throws QonfigInterpretationException {
		theTypeSet.renameEnum(this, newName);
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

	public ModifiableEnumValue addValue(LocatedPositionedContent name) throws QonfigInterpretationException {
		ModifiableEnumValue newValue = new ModifiableEnumValue(this, name.toString());
		if (!theValues.add(newValue))
			throw new QonfigInterpretationException("A value named '" + name + "' already exists in enum '" + theName + "'", name);
		return newValue;
	}

	public void delete(LocatedPositionedContent source) throws QonfigInterpretationException {
		if (!theReferences.isEmpty()) {
			StringBuilder str = new StringBuilder("There are ").append(theReferences.valueSize())
				.append(" entity fields that reference enum ").append(theName);
			for (ModifiableEntityField<EnumValue> field : theReferences.values())
				str.append("\n\t").append(field);
			throw new QonfigInterpretationException(str.toString(), source);
		}
		theTypeSet.removeEnum(this);
	}

	void renameValue(ModifiableEnumValue value, LocatedPositionedContent newName) throws QonfigInterpretationException {
		String nameStr = newName.toString();
		if (getValue(nameStr) != null)
			throw new QonfigInterpretationException("Another " + theName + " value named '" + nameStr + "' already exists", newName);
		theValues.remove(value);
		value.doSetName(nameStr);
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
