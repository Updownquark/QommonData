package org.qommons.data.types.modifiable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterHashMultiMap;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.DequeList;
import org.qommons.collect.ListElement;
import org.qommons.collect.MappedBetterSortedSet;
import org.qommons.collect.MappedSet;
import org.qommons.data.migration.MigrationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.FilePosition;
import org.qommons.tree.BetterTreeMultiMap;
import org.qommons.tree.BetterTreeSet;

public class ModifiableEntityType implements EntityType {
	private static final ModifiableEntityType[] ROOT_DESCENT = new ModifiableEntityType[0];

	private final ModifiableEntityTypeSet theTypeSet;
	private final ModifiableEntityType[] theDescent;
	private String theName;
	private final BetterSortedSet<ModifiableEntityField<?>> theLocalFields;
	private final DequeList<ModifiableEntityField<?>> theIdFields;
	private final BetterSortedSet<ModifiableEntityField<?>> allFields;
	private final Set<ModifiableEntityType> theSubTypes;
	private final BetterMultiMap<ModifiableEntityType, ModifiableEntityField<GenericEntity>> theReferences;
	private final Unmodifiable theUnmodifiable;
	private int[][] theFieldIndexes;

	ModifiableEntityType(ModifiableEntityTypeSet typeSet, ModifiableEntityType superType, String name) {
		theTypeSet = typeSet;
		theDescent = new ModifiableEntityType[superType.theDescent.length + 1];
		System.arraycopy(superType.theDescent, 0, theDescent, 0, superType.theDescent.length);
		theDescent[theDescent.length - 1] = superType;
		theName = name;
		theIdFields = superType.getIdFields();
		theLocalFields = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		allFields = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		allFields.addAll(superType.getFields());
		theSubTypes = new HashSet<>();
		theReferences = BetterHashMultiMap.create();
		superType.theSubTypes.add(this);
		theUnmodifiable = new Unmodifiable(this);
		theFieldIndexes = new int[theDescent.length][];
		theFieldIndexes[theDescent.length - 1] = new int[4];
		for (int i = 0; i < theFieldIndexes.length; i++)
			theFieldIndexes[i] = superType.theFieldIndexes[i].clone();
	}

	ModifiableEntityType(ModifiableEntityTypeSet typeSet, String name, Map<String, FieldType<?>> id, FilePosition source)
		throws MigrationException {
		theTypeSet = typeSet;
		theDescent = ROOT_DESCENT;
		theName = name;
		theLocalFields = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		allFields = BetterCollections.unmodifiableSortedSet(theLocalFields);
		theSubTypes = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		ModifiableEntityField<?>[] idFieldArray = new ModifiableEntityField[id.size()];
		int f = 0;
		for (Map.Entry<String, FieldType<?>> field : id.entrySet()) {
			try {
				idFieldArray[f++] = addField(field.getKey(), field.getValue(), source);
			} catch (MigrationException e) {
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
		theFieldIndexes = null;
	}

	@Override
	public ModifiableEntityTypeSet getTypeSet() {
		return theTypeSet;
	}

	@Override
	public String getName() {
		return theName;
	}

	public ModifiableEntityType setName(String newName, FilePosition source) throws MigrationException {
		theTypeSet.renameEntity(this, newName, source);
		return this;
	}

	void doSetName(String newName) {
		theName = newName;
	}

	@Override
	public ModifiableEntityType getSuperType() {
		return theDescent.length == 0 ? null : theDescent[theDescent.length - 1];
	}

	@Override
	public BetterSortedSet<ModifiableEntityField<?>> getLocalFields() {
		if (theDescent.length == 0)
			return allFields;
		else
			return BetterCollections.unmodifiableSortedSet(theLocalFields);
	}

	@Override
	public BetterSortedSet<ModifiableEntityField<?>> getFields() {
		if (theDescent.length == 0)
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
		if (field.getOwner() == this) {
			ModifiableEntityField<?> modField = (ModifiableEntityField<?>) field;
			if (theFieldIndexes == null) {
				// For root types, the field index is the same as the root index
				return modField.getRootIndex();
			} else
				return theFieldIndexes[theDescent.length][modField.getRootIndex()];
		} else if (theFieldIndexes == null || !(field instanceof ModifiableEntityField))
			return -1;
		ModifiableEntityField<?> modField = (ModifiableEntityField<?>) field;
		int depth = modField.getOwner().theDescent.length;
		if (depth < theDescent.length && theDescent[depth] == modField.getOwner())
			return theFieldIndexes[depth][modField.getRootIndex()];
		else
			return -1;
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

	public void delete(FilePosition source) throws MigrationException {
		if (!theReferences.isEmpty()) {
			StringBuilder str = new StringBuilder("There are ").append(theReferences.valueSize())
				.append(" entity fields that reference entity type ").append(theName);
			for (ModifiableEntityField<GenericEntity> field : theReferences.values())
				str.append("\n\t").append(field);
			throw new MigrationException(str.toString(), source);
		}
		theTypeSet.removeEntity(this);
	}

	private void checkNewField(String fieldName, FilePosition source, ModifiableEntityType fromSuperType) throws MigrationException {
		if (theLocalFields.search(f -> StringUtils.compareNumberTolerant(fieldName, f.getName(), true, true),
			SortedSearchFilter.OnlyMatch) != null) {
			if (fromSuperType != null)
				throw new MigrationException(
					"Field name " + theName + "." + fieldName + " clashes with a field of sub-type '" + theName + "'", source);
			else
				throw new MigrationException("A " + theName + " field named '" + fieldName + "' already exists", source);
		} else if (fromSuperType == null && theDescent.length > 0) {
			ModifiableEntityField<?> inh = getSuperType().getField(fieldName);
			if (inh != null)
				throw new MigrationException("A field named '" + fieldName + "' is inherited from super type " + inh.getOwner(), source);
		}
		for (ModifiableEntityType subType : theSubTypes)
			subType.checkNewField(fieldName, source, this);
	}

	public <F> ModifiableEntityField<F> addField(String name, FieldType<F> type, FilePosition source) throws MigrationException {
		checkNewField(name, source, null);
		if (type == FieldType.SELF)
			type = (FieldType<F>) this;
		ModifiableEntityField<F> field = new ModifiableEntityField<>(this, name, type, false);
		addLocalField(field);
		for (ModifiableEntityType subType : theSubTypes)
			subType.addInheritedField(field);
		if (type instanceof ModifiableEntityType)
			((ModifiableEntityType) type).theReferences.add(this, (ModifiableEntityField<GenericEntity>) field);
		else if (type instanceof ModifiableEnumType)
			((ModifiableEnumType) type).addReference((ModifiableEntityField<EnumValue>) field);
		return field;
	}

	private void regenFieldIndexes(ListElement<ModifiableEntityField<?>> start) {
		int index = start.getElementsBefore();
		for (; start != null; start = start.getAdjacent(true)) {
			theFieldIndexes[start.get().getOwner().theDescent.length][start.get().getRootIndex()] = index++;
		}
	}

	private void addLocalField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> added = theLocalFields.addElement(field, null, null, false);
		int index = added.getElementsBefore();
		field.setRootIndex(index);
		for (ListElement<ModifiableEntityField<?>> next = added.getAdjacent(true); next != null; next = next.getAdjacent(true))
			next.get().setRootIndex(++index);
		if (theFieldIndexes != null) { // We're a sub-type
			int[] localFieldIndexes = theFieldIndexes[theDescent.length];
			if (localFieldIndexes.length < theLocalFields.size()) {
				int[] newLFIs = new int[localFieldIndexes.length >> 1];
				System.arraycopy(localFieldIndexes, 0, newLFIs, 0, index);
				theFieldIndexes[theDescent.length] = newLFIs;
			}
			added = allFields.addElement(field, null, null, false);
			regenFieldIndexes(added);
		}
	}

	private void removeLocalField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> fieldEl = theLocalFields.getElement(field, true);
		int index = field.getRootIndex();
		for (ListElement<ModifiableEntityField<?>> next = fieldEl.getAdjacent(true); next != null; next = next.getAdjacent(true))
			next.get().setRootIndex(index++);
		theLocalFields.mutableElement(fieldEl.getElementId()).remove();
		field.setRootIndex(-1);
		if (theDescent.length > 0) {
			fieldEl = allFields.getElement(field, true);
			allFields.mutableElement(fieldEl.getElementId()).remove();
			regenFieldIndexes(fieldEl.getAdjacent(true));
		}
	}

	void addInheritedField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> added = allFields.addElement(field, null, null, false);
		if (theFieldIndexes != null) { // We're a sub-type
			ModifiableEntityType owner = field.getOwner();
			int[] fieldIndexes = theFieldIndexes[owner.theDescent.length];
			if (fieldIndexes.length < owner.theLocalFields.size()) {
				int[] newLFIs = new int[fieldIndexes.length >> 1];
				System.arraycopy(fieldIndexes, 0, newLFIs, 0, added.getElementsBefore());
				theFieldIndexes[owner.theDescent.length] = newLFIs;
			}
			added = allFields.addElement(field, null, null, false);
			regenFieldIndexes(added);
		}
		for (ModifiableEntityType subType : theSubTypes)
			subType.addInheritedField(field);
	}

	private void removeInheritedField(ModifiableEntityField<?> field) {
		ListElement<ModifiableEntityField<?>> removed = allFields.getElement(field, true);
		allFields.mutableElement(removed.getElementId()).remove();
		regenFieldIndexes(removed.getAdjacent(true));
		for (ModifiableEntityType subType : theSubTypes)
			subType.removeInheritedField(field);
	}

	void renameField(ModifiableEntityField<?> field, String newName, FilePosition source) throws MigrationException {
		checkNewField(newName, source, null);
		removeLocalField(field);
		for (ModifiableEntityType subType : theSubTypes)
			subType.removeInheritedField(field);
		field.doSetName(newName);
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
	}

	@Override
	public String toString() {
		return theName;
	}

	static class Unmodifiable implements EntityType {
		private final ModifiableEntityType theSource;
		private final EntityTypeSet theTypeSet;
		private final BetterSortedSet<EntityField<?>> theLocalFields;
		private final DequeList<? extends EntityField<?>> theIdFields;
		private final BetterSortedSet<EntityField<?>> allFields;
		private final Set<EntityType> theSubTypes;
		private final Set<EntityType> theReferrers;

		Unmodifiable(ModifiableEntityType source) {
			theSource = source;
			theTypeSet = source.getTypeSet().unmodifiableView();
			theLocalFields = new MappedBetterSortedSet<>(source.theLocalFields, ModifiableEntityField::unmodifiableView, null,
				Named.DISTINCT_NUMBER_TOLERANT);
			if (source.getSuperType() != null)
				theIdFields = source.getSuperType().unmodifiableView().getIdFields();
			else if (source.theIdFields.size() == 1)
				theIdFields = DequeList.of(source.theIdFields.getFirst().unmodifiableView());
			else {
				EntityField<?>[] idFields = new EntityField[source.theIdFields.size()];
				int f = 0;
				for (ModifiableEntityField<?> field : source.theIdFields)
					idFields[f++] = field.unmodifiableView();
				theIdFields = DequeList.of(BetterHashSet.build().build(idFields));
			}
			if (source.getSuperType() == null)
				allFields = theLocalFields;
			else
				allFields = new MappedBetterSortedSet<>(source.allFields, ModifiableEntityField::unmodifiableView, null,
					Named.DISTINCT_NUMBER_TOLERANT);
			theSubTypes = new MappedSet<>(source.theSubTypes, ModifiableEntityType::unmodifiableView,
				test -> theSource.theSubTypes.contains(((Unmodifiable) test).theSource));
			theReferrers = new MappedSet<>(source.theReferences.keySet(), ModifiableEntityType::unmodifiableView,
				test -> theSource.theReferences.keySet().contains(((Unmodifiable) test).theSource));
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
		public EntityType getSuperType() {
			ModifiableEntityType superType = theSource.getSuperType();
			return superType == null ? null : superType.unmodifiableView();
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
	}
}
