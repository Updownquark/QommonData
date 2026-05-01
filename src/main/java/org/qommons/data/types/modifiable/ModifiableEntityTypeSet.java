package org.qommons.data.types.modifiable;

import java.util.LinkedHashMap;
import java.util.Map;

import org.qommons.Named;
import org.qommons.QommonsUtils;
import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterSet;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.MappedBetterSortedSet;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldMapping;
import org.qommons.data.types.FieldType;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.tree.BetterTreeSet;

public class ModifiableEntityTypeSet implements EntityTypeSet {
	private final BetterSortedSet<ModifiableEntityType> theEntityTypes;
	private final BetterSortedSet<ModifiableEnumType> theEnumTypes;
	private final Unmodifiable theUnmodifiable;

	public ModifiableEntityTypeSet() {
		theEntityTypes = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		theEnumTypes = BetterTreeSet.createTreeSet(Named.DISTINCT_NUMBER_TOLERANT);
		theUnmodifiable = new Unmodifiable(this);
	}

	@Override
	public BetterSortedSet<ModifiableEntityType> getEntityTypes() {
		return BetterCollections.unmodifiableSortedSet(theEntityTypes);
	}

	@Override
	public ModifiableEntityType getEntityType(String name) {
		return (ModifiableEntityType) EntityTypeSet.super.getEntityType(name);
	}

	@Override
	public BetterSortedSet<ModifiableEnumType> getEnumTypes() {
		return theEnumTypes;
	}

	@Override
	public ModifiableEnumType getEnumType(String name) {
		return (ModifiableEnumType) EntityTypeSet.super.getEnumType(name);
	}

	public EntityTypeSet unmodifiableView() {
		return theUnmodifiable;
	}

	public ModifiableEntityType createEntityType(LocatedPositionedContent name, ModifiableEntityType[] superTypes)
		throws QonfigInterpretationException {
		if (getEntityType(name.toString()) != null)
			throw new QonfigInterpretationException("An entity type named '" + name + "' already exists", name);
		ModifiableEntityType newEntity = new ModifiableEntityType(this, superTypes, name);
		theEntityTypes.add(newEntity);
		return newEntity;
	}

	public ModifiableEntityType createEntityType(LocatedPositionedContent name, Map<LocatedPositionedContent, FieldType<?>> id)
		throws QonfigInterpretationException {
		String nameStr = name.toString();
		if (getEntityType(nameStr) != null)
			throw new QonfigInterpretationException("An entity type named '" + name + "' already exists", name);
		ModifiableEntityType newEntity = new ModifiableEntityType(this, name, id);
		theEntityTypes.add(newEntity);
		return newEntity;
	}

	public ModifiableEnumType createEnumType(LocatedPositionedContent name) throws QonfigInterpretationException {
		String nameStr = name.toString();
		if (getEnumType(nameStr) != null)
			throw new QonfigInterpretationException("An enum named '" + name + "' already exists", name);
		ModifiableEnumType newEnum = new ModifiableEnumType(this, nameStr);
		theEnumTypes.add(newEnum);
		return newEnum;
	}

	void renameEntity(ModifiableEntityType entityType, LocatedPositionedContent newName) throws QonfigInterpretationException {
		String nameStr = newName.toString();
		if (getEntityType(nameStr) != null)
			throw new QonfigInterpretationException("Another entity type named '" + newName + "' already exists", newName);
		theEntityTypes.remove(entityType);
		entityType.doSetName(nameStr);
		theEntityTypes.add(entityType);
	}

	void removeEntity(ModifiableEntityType entityType) {
		theEntityTypes.remove(entityType);
	}

	void renameEnum(ModifiableEnumType enumType, LocatedPositionedContent newName) throws QonfigInterpretationException {
		String nameStr = newName.toString();
		if (getEnumType(nameStr) != null)
			throw new QonfigInterpretationException("Another enum named '" + newName + "' already exists", newName);
		theEnumTypes.remove(enumType);
		enumType.doSetName(nameStr);
		theEnumTypes.add(enumType);
	}

	void removeEnum(ModifiableEnumType enumType) {
		theEnumTypes.remove(enumType);
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder();
		if (!theEnumTypes.isEmpty()) {
			str.append("Enums:");
			for (ModifiableEnumType enumType : theEnumTypes)
				enumType.append(str.append("\n\t"), 1);
		}
		if (!theEntityTypes.isEmpty()) {
			if (!theEnumTypes.isEmpty())
				str.append('\n');
			str.append("Entities:");
			for (ModifiableEntityType entityType : theEntityTypes)
				entityType.append(str.append("\n\t"), 1);
		}
		return str.toString();
	}

	public ModifiableEntityTypeSet copy(EntityTypeSet source) {
		try {
			// Enums have no dependencies, so these are easy
			for (EnumType sourceEnum : source.getEnumTypes()) {
				copyEnum(sourceEnum);
			}
			// Entities have to be done in stages because they have dependencies on each other which may be cyclical.
			// First, check all the types that are in common. These must match exactly.
			for (EntityType sourceEntity : source.getEntityTypes()) {
				ModifiableEntityType destEntity = getEntityType(sourceEntity.getName());
				if (destEntity != null) {
					QommonsUtils.<EntityField<?>, ModifiableEntityField<?>, IllegalArgumentException> compareCollections(
						sourceEntity.getFields(), destEntity.getFields(), //
						(f1, f2) -> f1.getName().equals(f2.getName()) && f1.isId() == f2.isId() && compareTypes(f1.getType(), f2.getType())
						&& compareMapping(f1.getMapping(), f2.getMapping()), //
						(f1, f2) -> {
							if (f1 == null)
								throw new IllegalArgumentException(
									"Entity types " + sourceEntity.getName() + " do not match: source entity is missing field " + f2);
							else if (f2 == null || !f1.getName().equals(f2.getName()))
								throw new IllegalArgumentException(
									"Entity types " + sourceEntity.getName() + " do not match: destination entity is missing field " + f1);
							else if (f1.isId() != f2.isId())
								throw new IllegalArgumentException(
									"Entity types " + sourceEntity.getName() + " do not match: ID field sets are mismatched");
							else if (!compareTypes(f1.getType(), f2.getType()))
								throw new IllegalArgumentException(
									"Entity types " + sourceEntity.getName() + " do not match: the types of field " + f1.getName()
									+ " are different: " + f1.getType() + " and " + f2.getType());
							else if (!compareMapping(f1.getMapping(), f2.getMapping()))
								throw new IllegalArgumentException(
									"Entity types " + sourceEntity.getName() + " do not match: the mappings of field " + f1.getName()
									+ " are different: " + f1.getMapping() + " and " + f2.getMapping());
							else
								throw new IllegalArgumentException("Unhandled field difference for " + f1);
						});
				}
			}
			for (EntityType sourceEntity : source.getEntityTypes()) {
				getOrCreateEntity(sourceEntity);
			}
			BetterSet<EntityField<?>> populatingFields = BetterHashSet.create();
			for (EntityType sourceEntity : source.getEntityTypes()) {
				populateEntity(sourceEntity, getEntityType(sourceEntity.getName()), populatingFields);
			}
		} catch (QonfigInterpretationException e) {
			throw new IllegalStateException("This shouldn't happen", e);
		}
		return this;
	}

	private static boolean compareTypes(FieldType<?> type1, FieldType<?> type2) {
		if (type1 == type2)
			return true;
		else if (type1 instanceof EnumType)
			return type2 instanceof EnumType && ((EnumType) type1).getName().equals(((EnumType) type2).getName());
		else if (type1 instanceof EntityType)
			return type2 instanceof EntityType && ((EntityType) type1).getName().equals(((EntityType) type2).getName());
		else if (type1 instanceof FieldType.ParameterizedType && type2 instanceof FieldType.ParameterizedType) {
			FieldType.ParameterizedType<?> pt1 = (FieldType.ParameterizedType<?>) type1;
			FieldType.ParameterizedType<?> pt2 = (FieldType.ParameterizedType<?>) type2;
			return pt1.rawTypesEqual(pt2) && QommonsUtils.compareCollections(pt1.getTypeParameters(), pt2.getTypeParameters(),
				ModifiableEntityTypeSet::compareTypes, null);
		} else
			return false;
	}

	private boolean compareMapping(FieldMapping<?, ?, ?> m1, FieldMapping<?, ?, ?> m2) {
		return m1.parentIsOwner == m2.parentIsOwner//
			&& m1.mappedReferenceField.getName().equals(m2.mappedReferenceField.getName())//
			&& (m1.keyField == null ? m2.keyField == null : m1.keyField.getName().equals(m2.keyField.getName()))//
			&& (m1.indexField == null ? m2.indexField == null : m1.indexField.getName().equals(m2.indexField.getName()))//
			&& (m1.sortByField == null ? m2.sortByField == null : m1.sortByField.getName().equals(m2.sortByField.getName()));
	}

	private void copyEnum(EnumType sourceEnum) throws QonfigInterpretationException {
		ModifiableEnumType destEnum = getEnumType(sourceEnum.getName());
		if (destEnum == null) {
			destEnum = createEnumType(LocatedPositionedContent.of(null, sourceEnum.getName()));
			for (EnumValue sourceValue : sourceEnum.getValues())
				destEnum.addValue(LocatedPositionedContent.of(null, sourceValue.getName()));
		} else {
			QommonsUtils.compareCollections(sourceEnum.getValues(), destEnum.getValues(),
				(src, dest) -> src.getName().equals(dest.getName()), (src, dest) -> {
					if (src == null)
						throw new IllegalArgumentException(
							"Enum types " + sourceEnum.getName() + " do not match: source is missing value " + dest.getName());
					else
						throw new IllegalArgumentException(
							"Enum types " + sourceEnum.getName() + " do not match: destination is missing value " + src.getName());
				});
		}
	}

	private ModifiableEntityType getOrCreateEntity(EntityType sourceEntity) throws QonfigInterpretationException {
		ModifiableEntityType destEntity = getEntityType(sourceEntity.getName());
		if (destEntity == null) {
			if (sourceEntity.getSuperTypes().isEmpty()) {
				Map<LocatedPositionedContent, FieldType<?>> ids = new LinkedHashMap<>();
				for (EntityField<?> field : sourceEntity.getIdFields())
					ids.put(LocatedPositionedContent.of(null, field.getName()), copyFieldType(field.getType()));
				return createEntityType(LocatedPositionedContent.of(null, sourceEntity.getName()), ids);
			} else {
				ModifiableEntityType[] supers = new ModifiableEntityType[sourceEntity.getSuperTypes().size()];
				int s = 0;
				for (EntityType superType : sourceEntity.getSuperTypes())
					supers[s++] = getOrCreateEntity(superType);
				return createEntityType(LocatedPositionedContent.of(null, sourceEntity.getName()), supers);
			}
		} else {
			QommonsUtils.compareCollections(sourceEntity.getIdFields(), destEntity.getIdFields(), //
				(src, dest) -> src.getName().equals(dest.getName()) && compareTypes(src.getType(), dest.getType()), //
				(src, dest) -> {
					if (src == null)
						throw new IllegalArgumentException(
							"Entity types " + sourceEntity.getName() + " do not match: source is missing ID field " + dest.getName());
					else if (dest == null || !src.getName().equals(dest.getName()))
						throw new IllegalArgumentException(
							"Entity types " + sourceEntity.getName() + " do not match: destination is missing ID field " + src.getName());
					else
						throw new IllegalArgumentException(
							"Entity types " + sourceEntity.getName() + " do not match: type of source ID field " + src
							+ " does not match that of the destination: " + dest.getType());
				});
		}
		return destEntity;
	}

	private <T, F extends FieldType<T>> F copyFieldType(F sourceType) throws QonfigInterpretationException {
		if (sourceType instanceof FieldType.SimpleType || sourceType == FieldType.BLOB)
			return sourceType;
		else if (sourceType instanceof EnumType)
			return (F) getEnumType(((EnumType) sourceType).getName());
		else if (sourceType instanceof EntityType)
			return (F) getOrCreateEntity((EntityType) sourceType);
		else if (sourceType instanceof FieldType.ParameterizedType)
			return (F) ((FieldType.ParameterizedType<T>) sourceType).map(this::copyFieldType);
		else
			throw new IllegalStateException("Unhandled field type: " + sourceType);
	}

	private void populateEntity(EntityType sourceEntity, ModifiableEntityType destEntity, BetterSet<EntityField<?>> populatingFields)
		throws QonfigInterpretationException {
		for (EntityField<?> sourceField : sourceEntity.getFields()) {
			if (sourceField.isId() || destEntity.getField(sourceField.getName()) != null)
				continue; // Already taken care of
			copyField(sourceField, destEntity, populatingFields);
		}
	}

	private ModifiableEntityField<?> copyField(EntityField<?> sourceField, ModifiableEntityType destEntity,
		BetterSet<EntityField<?>> populatingFields) throws QonfigInterpretationException {
		FieldType<?> type = copyFieldType(sourceField.getType());
		FieldMappingPrecursor<?, ?> mapping;
		if (sourceField.getMapping() == null)
			mapping = null;
		else {
			CollectionElement<EntityField<?>> adding = populatingFields.addElement(sourceField, false);
			if (adding == null)
				throw new IllegalArgumentException("Unresolvable cyclical field mapping dependency: " + populatingFields);
			FieldMapping<?, ?, ?> m = sourceField.getMapping();
			ModifiableEntityType referenceType = getEntityType(m.mappedReferenceField.getOwner().getName());
			mapping = new FieldMappingPrecursor<>(destEntity, LocatedPositionedContent.of(null, sourceField.getName()), type,
				LocatedPositionedContent.of(null, m.mappedReferenceField.getName()), //
				m.keyField == null ? null
					: LocatedPositionedContent.of(null, copyField(m.keyField, referenceType, populatingFields).getName()), //
					m.indexField == null ? null
						: LocatedPositionedContent.of(null, copyField(m.indexField, referenceType, populatingFields).getName()), //
						m.sortByField == null ? null
							: LocatedPositionedContent.of(null, copyField(m.sortByField, referenceType, populatingFields).getName()), //
							m.parentIsOwner);
			populatingFields.mutableElement(adding.getElementId()).remove();
		}
		return destEntity.addField(LocatedPositionedContent.of(null, sourceField.getName()), type, mapping);
	}

	static class Unmodifiable implements EntityTypeSet {
		private final BetterSortedSet<EntityType> theEntityTypes;
		private final BetterSortedSet<EnumType> theEnumTypes;

		Unmodifiable(ModifiableEntityTypeSet source) {
			theEntityTypes = BetterCollections.unmodifiableSortedSet(new MappedBetterSortedSet<>(source.theEntityTypes,
				ModifiableEntityType::unmodifiableView, null, Named.DISTINCT_NUMBER_TOLERANT));
			theEnumTypes = BetterCollections.unmodifiableSortedSet(new MappedBetterSortedSet<>(source.theEnumTypes,
				ModifiableEnumType::unmodifiableView, null, Named.DISTINCT_NUMBER_TOLERANT));
		}

		@Override
		public BetterSortedSet<? extends EntityType> getEntityTypes() {
			return theEntityTypes;
		}

		@Override
		public BetterSortedSet<? extends EnumType> getEnumTypes() {
			return theEnumTypes;
		}
	}
}
