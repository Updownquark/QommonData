package org.qommons.data.types.modifiable;

import java.util.Map;

import org.qommons.Named;
import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.MappedBetterSortedSet;
import org.qommons.data.migration.MigrationException;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.FieldType;
import org.qommons.io.FilePosition;
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

	public ModifiableEntityType createEntityType(String name, ModifiableEntityType[] superTypes, FilePosition source)
		throws MigrationException {
		if (getEntityType(name) != null)
			throw new MigrationException("An entity type named '" + name + "' already exists", source);
		ModifiableEntityType newEntity = new ModifiableEntityType(this, superTypes, name, source);
		theEntityTypes.add(newEntity);
		return newEntity;
	}

	public ModifiableEntityType createEntityType(String name, Map<String, FieldType<?>> id, FilePosition source) throws MigrationException {
		if (getEntityType(name) != null)
			throw new MigrationException("An entity type named '" + name + "' already exists", source);
		ModifiableEntityType newEntity = new ModifiableEntityType(this, name, id, source);
		theEntityTypes.add(newEntity);
		return newEntity;
	}

	public ModifiableEnumType createEnumType(String name, FilePosition source) throws MigrationException {
		if (getEnumType(name) != null)
			throw new MigrationException("An enum named '" + name + "' already exists", source);
		ModifiableEnumType newEnum = new ModifiableEnumType(this, name);
		theEnumTypes.add(newEnum);
		return newEnum;
	}

	void renameEntity(ModifiableEntityType entityType, String newName, FilePosition source) throws MigrationException {
		if (getEntityType(newName) != null)
			throw new MigrationException("Another entity type named '" + newName + "' already exists", source);
		theEntityTypes.remove(entityType);
		entityType.doSetName(newName);
		theEntityTypes.add(entityType);
	}

	void removeEntity(ModifiableEntityType entityType) {
		theEntityTypes.remove(entityType);
	}

	void renameEnum(ModifiableEnumType enumType, String newName, FilePosition source) throws MigrationException {
		if (getEnumType(newName) != null)
			throw new MigrationException("Another enum named '" + newName + "' already exists", source);
		theEnumTypes.remove(enumType);
		enumType.doSetName(newName);
		theEnumTypes.add(enumType);
	}

	void removeEnum(ModifiableEnumType enumType) {
		theEnumTypes.remove(enumType);
	}

	static class Unmodifiable implements EntityTypeSet {
		private final BetterSortedSet<EntityType> theEntityTypes;
		private final BetterSortedSet<EnumType> theEnumTypes;

		Unmodifiable(ModifiableEntityTypeSet source) {
			theEntityTypes = new MappedBetterSortedSet<>(source.theEntityTypes, ModifiableEntityType::unmodifiableView, null,
				Named.DISTINCT_NUMBER_TOLERANT);
			theEnumTypes = new MappedBetterSortedSet<>(source.theEnumTypes, ModifiableEnumType::unmodifiableView, null,
				Named.DISTINCT_NUMBER_TOLERANT);
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
