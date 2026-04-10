package org.qommons.data.impl;

import java.util.Map;
import java.util.Set;

import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterSortedSet;
import org.qommons.data.migration.MigrationSetDef;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.tree.BetterTreeSet;

public class InMemoryMigratableEntitySet extends InMemoryEntitySet implements MigratableDataSet {
	private final BetterSortedSet<MigrationSetDef> theMigrations;

	public InMemoryMigratableEntitySet(ModifiableEntityTypeSet dataTypes) {
		super(dataTypes);
		theMigrations = BetterTreeSet.createTreeSet(MigrationSetDef.SORT);
	}

	@Override
	public ModifiableEntityTypeSet getTypes() {
		return (ModifiableEntityTypeSet) super.getTypes();
	}

	@Override
	public void entityTypeCreated(EntityType entityType) throws DataSetModificationException {
		super.entityTypeCreated(entityType);
	}

	@Override
	public void entityTypeRemoved(EntityType entityType) throws DataSetModificationException {
		super.entityTypeRemoved(entityType);
	}

	@Override
	public void entityTypeRenamed(EntityType entityType, String oldName) throws DataSetModificationException {
		super.entityTypeRenamed(entityType, oldName);
	}

	@Override
	public <F> void entityFieldAdded(EntityField<F> field, F initValue) throws DataSetModificationException {
		super.entityFieldAdded(field, initValue);
	}

	@Override
	public void entityFieldRemoved(EntityField field) throws DataSetModificationException {
		super.entityFieldRemoved(field);
	}

	@Override
	public void entityFieldRenamed(EntityField field, String oldName) throws DataSetModificationException {
		super.entityFieldRenamed(field, oldName);
	}

	@Override
	public void entityAffected(EntityType entityType) {
		super.entityAffected(entityType);
	}

	@Override
	public GenericEntitySet createView(Set<String> affectedEntities, Map<String, Set<String>> requiredFields)
		throws DataSetModificationException {
		return new FilteredEntitySetView(this, affectedEntities, requiredFields);
	}

	@Override
	public BetterSortedSet<MigrationSetDef> getAppliedMigrations() {
		return BetterCollections.unmodifiableSortedSet(theMigrations);
	}

	@Override
	public void migrationApplied(MigrationSetDef migration) {
		theMigrations.add(migration);
	}

	@Override
	public GenericEntitySet immutableSchema(EntityTypeSet codeTypes) {
		GenericEntitySet immutable = new InMemoryEntitySet(codeTypes);
		for (EntityType type : codeTypes.getEntityTypes()) {
			if (type.getSuperType() == null) {
				for (GenericEntity entity : getEntities(type.getName())) {
					copyEntity(entity, immutable);
				}
			}
		}
		return immutable;
	}

	private void copyEntity(GenericEntity entity, GenericEntitySet entitySet) {
		GenericEntity copy = entitySet.createEntity(entity.getType().getName(), entity.getId());
		for (EntityField<?> field : entity.getType().getFields()) {
			if (!field.isId())
				copy.set(field, entity.get(field));
		}
	}
}
