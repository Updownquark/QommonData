package org.qommons.data.types.modifiable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.qommons.IterableUtils;
import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterHashMultiMap;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSet;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.DequeList;
import org.qommons.collect.ListElement;
import org.qommons.collect.MappedBetterSet;
import org.qommons.collect.MappedBetterSortedSet;
import org.qommons.collect.MappedSet;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.tree.BetterTreeMultiMap;
import org.qommons.tree.BetterTreeSet;

public class ModifiableEntityType implements EntityType {
	private final ModifiableEntityTypeSet theTypeSet;
	private final BetterSet<ModifiableEntityType> theSuperTypes;
	private String theName;
	private final BetterSortedSet<ModifiableEntityField<?>> theLocalFields;
	private final DequeList<ModifiableEntityField<?>> theIdFields;
	private final BetterSortedSet<ModifiableEntityField<?>> allFields;
	private final Map<ModifiableEntityField<?>, Integer> theFieldIndexes;
	private final Set<ModifiableEntityType> theSubTypes;
	private final BetterMultiMap<ModifiableEntityType, ModifiableEntityField<GenericEntity>> theReferences;
	private final Unmodifiable theUnmodifiable;

	ModifiableEntityType(ModifiableEntityTypeSet typeSet, ModifiableEntityType[] superTypes, LocatedPositionedContent name)
		throws QonfigInterpretationException {
		theTypeSet = typeSet;
		if (superTypes.length == 1)
			theSuperTypes = BetterSet.single(superTypes[0]);
		else
			theSuperTypes = BetterCollections.unmodifiableSet(BetterHashSet.<ModifiableEntityType> create().with(superTypes));
		theName = name.toString();
		theLocalFields = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		allFields = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		ModifiableEntityType firstSuperRoot = null;
		for (ModifiableEntityType sup : superTypes) {
			if (firstSuperRoot == null)
				firstSuperRoot = sup.getRootType();
			else if (firstSuperRoot != sup.getRootType())
				throw new QonfigInterpretationException("All super types must extend the same root type: " + sup + " does not extend "
					+ firstSuperRoot + " as " + superTypes[0] + " does", name);
			for (ModifiableEntityField<?> field : sup.getFields()) {
				CollectionElement<ModifiableEntityField<?>> el = allFields.getOrAdd(field, null, null, false, null, null);
				if (el.get() != field)
					throw new QonfigInterpretationException("Super types '" + el.get().getOwner() + "' is incompatible with '" + sup
						+ "': Conflicting fields named '" + field.getName() + "'", name);
			}
			sup.theSubTypes.add(this);
		}
		theFieldIndexes = new HashMap<>();
		int f = 0;
		for (ModifiableEntityField<?> field : allFields)
			theFieldIndexes.put(field, f++);
		theIdFields = firstSuperRoot.getIdFields();
		theSubTypes = new HashSet<>();
		theReferences = BetterHashMultiMap.create();
		theUnmodifiable = new Unmodifiable(this);
	}

	ModifiableEntityType(ModifiableEntityTypeSet typeSet, LocatedPositionedContent name, Map<LocatedPositionedContent, FieldType<?>> id)
		throws QonfigInterpretationException {
		theTypeSet = typeSet;
		theSuperTypes = BetterSet.empty();
		theName = name.toString();
		theLocalFields = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		allFields = BetterCollections.unmodifiableSortedSet(theLocalFields);
		theSubTypes = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		ModifiableEntityField<?>[] idFieldArray = new ModifiableEntityField[id.size()];
		int f = 0;
		for (Map.Entry<LocatedPositionedContent, FieldType<?>> field : id.entrySet()) {
			try {
				idFieldArray[f++] = addField(field.getKey(), field.getValue(), null, true);
			} catch (QonfigInterpretationException e) {
				for (ModifiableEntityField<?> f2 : theLocalFields)
					f2.delete();
				throw e;
			}
		}
		if (f == 1)
			theIdFields = DequeList.of(idFieldArray[0]);
		else
			theIdFields = DequeList.of(idFieldArray);
		theReferences = BetterTreeMultiMap.create(Named.DISTINCT_NUMBER_TOLERANT,
			b -> b.withSortedValues(Named.DISTINCT_NUMBER_TOLERANT, true));
		theUnmodifiable = new Unmodifiable(this);
		theFieldIndexes = new HashMap<>();
		f = 0;
		for (ModifiableEntityField<?> field : allFields)
			theFieldIndexes.put(field, f++);
	}

	@Override
	public ModifiableEntityTypeSet getTypeSet() {
		return theTypeSet;
	}

	@Override
	public String getName() {
		return theName;
	}

	public ModifiableEntityType setName(LocatedPositionedContent newName) throws QonfigInterpretationException {
		theTypeSet.renameEntity(this, newName);
		return this;
	}

	void doSetName(String newName) {
		theName = newName;
	}

	@Override
	public BetterSet<ModifiableEntityType> getSuperTypes() {
		return BetterCollections.unmodifiableSet(theSuperTypes);
	}

	@Override
	public ModifiableEntityType getRootType() {
		return (ModifiableEntityType) EntityType.super.getRootType();
	}

	@Override
	public BetterSortedSet<ModifiableEntityField<?>> getLocalFields() {
		if (theSuperTypes.isEmpty())
			return allFields;
		else
			return BetterCollections.unmodifiableSortedSet(theLocalFields);
	}

	@Override
	public BetterSortedSet<ModifiableEntityField<?>> getFields() {
		if (theSuperTypes.isEmpty())
			return allFields;
		else
			return BetterCollections.unmodifiableSortedSet(allFields);
	}

	@Override
	public DequeList<ModifiableEntityField<?>> getIdFields() {
		return theIdFields;
	}

	@Override
	public ModifiableEntityField<?> getField(String name) {
		return (ModifiableEntityField<?>) EntityType.super.getField(name);
	}

	@Override
	public int indexOf(EntityField<?> field) {
		Integer idx = theFieldIndexes.get(field);
		return idx == null ? -1 : idx.intValue();
	}

	@Override
	public Set<ModifiableEntityType> getSubTypes() {
		return theSubTypes;
	}

	@Override
	public Set<? extends ModifiableEntityType> getReferrers() {
		return Collections.unmodifiableSet(theReferences.keySet());
	}

	@Override
	public Set<? extends ModifiableEntityField<GenericEntity>> getReferences(EntityType type) {
		return Collections
			.unmodifiableSet((Set<? extends ModifiableEntityField<GenericEntity>>) theReferences.get((ModifiableEntityType) type));
	}

	public EntityType unmodifiableView() {
		return theUnmodifiable;
	}

	public void delete(LocatedPositionedContent source) throws QonfigInterpretationException {
		if (!theReferences.isEmpty()) {
			StringBuilder str = new StringBuilder("There are ").append(theReferences.valueSize())
				.append(" entity fields that reference entity type ").append(theName);
			for (ModifiableEntityField<GenericEntity> field : theReferences.values())
				str.append("\n\t").append(field);
			throw new QonfigInterpretationException(str.toString(), source);
		}
		theTypeSet.removeEntity(this);
	}

	private void checkNewField(LocatedPositionedContent fieldName, ModifiableEntityType fromSuperType)
		throws QonfigInterpretationException {
		String fieldStr = fieldName.toString();
		ModifiableEntityField<?> clash = allFields.searchValue(f -> StringUtils.compareNumberTolerant(fieldStr, f.getName(), true, true),
			SortedSearchFilter.OnlyMatch);
		if (clash != null) {
			if (clash.getOwner() == this) {
				if (fromSuperType != null)
					throw new QonfigInterpretationException(
						"Field " + theName + "." + fieldStr + " clashes with a field of sub-type '" + theName + "'", fieldName);
				else
					throw new QonfigInterpretationException("A " + theName + " field named '" + fieldStr + "' already exists", fieldName);
			} else {
				if (fromSuperType != null)
					throw new QonfigInterpretationException(
						"Field " + theName + "." + fieldStr + " clashes with a field of sub-type '" + theName + "' which inherits " + clash,
						fieldName);
				else
					throw new QonfigInterpretationException(
						"Field name " + theName + "." + fieldStr + " clashes with a field of super-type '" + clash.getOwner(), fieldName);
			}
		}
		for (ModifiableEntityType subType : theSubTypes)
			subType.checkNewField(fieldName, this);
	}

	public <F> ModifiableEntityField<F> addField(LocatedPositionedContent name, FieldType<F> type, FieldMappingPrecursor<?, ?> mapping)
		throws QonfigInterpretationException {
		return addField(name, type, mapping, false);
	}

	private <F> ModifiableEntityField<F> addField(LocatedPositionedContent name, FieldType<F> type, FieldMappingPrecursor<?, ?> mapping,
		boolean id) throws QonfigInterpretationException {
		checkNewField(name, null);
		if (type == FieldType.SELF)
			type = (FieldType<F>) this;
		if (mapping != null) {
			if (mapping.mappedReferenceField.getMappingReference() != null)
				throw new QonfigInterpretationException(
					"Mapped reference field " + mapping.mappedReferenceField.getOwner() + "." + mapping.mappedReferenceField.getName()
					+ " is already mapped to " + mapping.mappedReferenceField.getMappingReference().parentField,
					name);
			if (mapping.indexField != null && mapping.indexField.getIndexReference() != null)
				throw new QonfigInterpretationException("Mapped index field " + mapping.indexField.getOwner() + "."
					+ mapping.indexField.getName() + " is already the index for " + mapping.indexField.getIndexReference().parentField,
					name);
		}
		ModifiableEntityField<F> field = new ModifiableEntityField<>(this, name.toString(), type, id, mapping);
		addLocalField(field);
		for (ModifiableEntityType subType : theSubTypes)
			subType.addInheritedField(field);
		if (type instanceof ModifiableEntityType)
			((ModifiableEntityType) type).theReferences.add(this, (ModifiableEntityField<GenericEntity>) field);
		else if (type instanceof ModifiableEnumType)
			((ModifiableEnumType) type).addReference((ModifiableEntityField<EnumValue>) field);

		if (mapping != null) {
			mapping.mappedReferenceField.setReference(field.getMapping());
			if (mapping.keyField != null)
				mapping.keyField.addAncillaryReference(field.getMapping());
			if (mapping.indexField != null)
				mapping.indexField.setIndexReference(field.getMapping());
			if (mapping.sortByField != null)
				mapping.sortByField.addAncillaryReference(field.getMapping());
		}
		return field;
	}

	private void regenFieldIndexes(ListElement<ModifiableEntityField<?>> start) {
		if (start == null || theFieldIndexes == null) // indexes is null if initializing
			return;
		int index = start.getElementsBefore();
		for (; start != null; start = start.getAdjacent(true))
			theFieldIndexes.put(start.get(), index++);
	}

	private void addLocalField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> added = theLocalFields.addElement(field, null, null, false);
		if (theSubTypes.isEmpty()) { // We're a root type, so allFields is an unmodifiable view of theLocalFields
			regenFieldIndexes(added);
		} else
			regenFieldIndexes(allFields.addElement(field, false));
	}

	private void removeLocalField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> remove = theLocalFields.getElement(field, true);
		theLocalFields.mutableElement(remove.getElementId()).remove();
		theFieldIndexes.remove(field);
		if (theSubTypes.isEmpty()) { // We're a root type, so allFields is an unmodifiable view of theLocalFields
			regenFieldIndexes(remove.getAdjacent(true));
		} else {
			remove = allFields.getElement(field, true);
			allFields.mutableElement(remove.getElementId()).remove();
			regenFieldIndexes(remove.getAdjacent(true));
		}
	}

	void addInheritedField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> added = allFields.addElement(field, null, null, false);
		regenFieldIndexes(added);
		for (ModifiableEntityType subType : theSubTypes)
			subType.addInheritedField(field);
	}

	private void removeInheritedField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> removed = allFields.getElement(field, true);
		allFields.mutableElement(removed.getElementId()).remove();
		theFieldIndexes.remove(field);
		regenFieldIndexes(removed.getAdjacent(true));
		for (ModifiableEntityType subType : theSubTypes)
			subType.removeInheritedField(field);
	}

	void renameField(ModifiableEntityField<?> field, LocatedPositionedContent newName) throws QonfigInterpretationException {
		checkNewField(newName, null);
		removeLocalField(field);
		for (ModifiableEntityType subType : theSubTypes)
			subType.removeInheritedField(field);
		field.doSetName(newName.toString());
		addLocalField(field);
		for (ModifiableEntityType subType : theSubTypes)
			subType.addInheritedField(field);
	}

	void removeField(ModifiableEntityField<?> field) {
		if (field.getType() instanceof ModifiableEntityType)
			((ModifiableEntityType) field.getType()).theReferences.remove(this, field);
		else if (field.getType() instanceof ModifiableEnumType)
			((ModifiableEnumType) field.getType()).removeReference((ModifiableEntityField<EnumValue>) field);
		removeLocalField(field);
		for (ModifiableEntityType subType : theSubTypes)
			subType.removeInheritedField(field);

		if (field.getMapping() != null) {
			((ModifiableEntityField<?>) field.getMapping().mappedReferenceField).setReference(null);
			if (field.getMapping().keyField != null)
				((ModifiableEntityField<?>) field.getMapping().keyField).removeAncillaryReference(field.getMapping());
			if (field.getMapping().indexField != null)
				((ModifiableEntityField<?>) field.getMapping().indexField).setIndexReference(null);
			if (field.getMapping().sortByField != null)
				((ModifiableEntityField<?>) field.getMapping().sortByField).removeAncillaryReference(field.getMapping());
		}
	}

	public StringBuilder append(StringBuilder str, int indent) {
		str.append(theName);
		if (!theSuperTypes.isEmpty()) {
			str.append(" extends ");
			boolean first = true;
			for (ModifiableEntityType superT : theSuperTypes) {
				if (first)
					first = false;
				else
					str.append(", ");
				str.append(superT.getName());
			}
		}
		for (ModifiableEntityField<?> field : theLocalFields)
			field.append(StringUtils.indent(str.append('\n'), indent + 1));
		return str;
	}

	@Override
	public String toString() {
		return theName;
	}

	static class Unmodifiable implements EntityType {
		private final ModifiableEntityType theSource;
		private final EntityTypeSet theTypeSet;
		private final BetterSet<EntityType> theSuperTypes;
		private final BetterSortedSet<EntityField<?>> theLocalFields;
		private final DequeList<? extends EntityField<?>> theIdFields;
		private final BetterSortedSet<EntityField<?>> allFields;
		private final Set<EntityType> theSubTypes;
		private final Set<EntityType> theReferrers;

		Unmodifiable(ModifiableEntityType source) {
			theSource = source;
			theTypeSet = source.getTypeSet().unmodifiableView();
			theLocalFields = BetterCollections.unmodifiableSortedSet(new MappedBetterSortedSet<>(source.theLocalFields,
				ModifiableEntityField::unmodifiableView, null, Named.DISTINCT_NUMBER_TOLERANT));
			theIdFields = DequeList.of(IterableUtils.map(source.theIdFields, ModifiableEntityField::unmodifiableView));
			if (source.getSuperTypes().isEmpty()) {
				theSuperTypes = BetterSet.empty();
				allFields = theLocalFields;
			} else {
				theSuperTypes = BetterCollections.unmodifiableSet(new MappedBetterSet<>(source.theSuperTypes,
					ModifiableEntityType::unmodifiableView, test -> theSource.theSuperTypes.contains(((Unmodifiable) test).theSource),
					v -> v == null ? null : ((Unmodifiable) v).theSource));
				allFields = BetterCollections.unmodifiableSortedSet(new MappedBetterSortedSet<>(source.allFields,
					ModifiableEntityField::unmodifiableView, null, Named.DISTINCT_NUMBER_TOLERANT));
			}
			theSubTypes = Collections.unmodifiableSet(new MappedSet<>(source.theSubTypes, ModifiableEntityType::unmodifiableView,
				test -> theSource.theSubTypes.contains(((Unmodifiable) test).theSource)));
			theReferrers = Collections
				.unmodifiableSet(new MappedSet<>(source.theReferences.keySet(), ModifiableEntityType::unmodifiableView,
					test -> theSource.theReferences.keySet().contains(((Unmodifiable) test).theSource)));
		}

		ModifiableEntityType getSource() {
			return theSource;
		}

		@Override
		public EntityTypeSet getTypeSet() {
			return theTypeSet;
		}

		@Override
		public String getName() {
			return theSource.getName();
		}

		@Override
		public BetterSet<EntityType> getSuperTypes() {
			return theSuperTypes;
		}

		@Override
		public BetterSortedSet<? extends EntityField<?>> getLocalFields() {
			return theLocalFields;
		}

		@Override
		public BetterSortedSet<? extends EntityField<?>> getFields() {
			return allFields;
		}

		@Override
		public DequeList<? extends EntityField<?>> getIdFields() {
			return theIdFields;
		}

		@Override
		public int indexOf(EntityField<?> field) {
			return theSource.indexOf(((ModifiableEntityField.Unmodifiable<?>) field).getSource());
		}

		@Override
		public Set<? extends EntityType> getSubTypes() {
			return theSubTypes;
		}

		@Override
		public Set<? extends EntityType> getReferrers() {
			return theReferrers;
		}

		@Override
		public Set<? extends EntityField<GenericEntity>> getReferences(EntityType type) {
			Set<ModifiableEntityField<GenericEntity>> refs = (Set<ModifiableEntityField<GenericEntity>>) theSource.theReferences
				.get(((Unmodifiable) type).theSource);
			return new MappedSet<>(refs, ModifiableEntityField::unmodifiableView,
				test -> refs.contains(((ModifiableEntityField.Unmodifiable<?>) test).getSource()));
		}

		@Override
		public String toString() {
			return theSource.toString();
		}
	}
}
