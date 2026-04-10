package org.qommons.data.impl;

import java.util.Map;
import java.util.Set;

import org.qommons.collect.BetterSortedSet;
import org.qommons.data.migration.MigrationSetDef;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntitySet;

public interface MigratableDataSet extends GenericEntitySet {
	@Override
	ModifiableEntityTypeSet getTypes();

	void entityTypeCreated(EntityType entityType) throws DataSetModificationException;

	void entityTypeRemoved(EntityType entityType) throws DataSetModificationException;

	void entityTypeRenamed(EntityType entityType, String oldName) throws DataSetModificationException;

	<F> void entityFieldAdded(EntityField<F> field, F initValue) throws DataSetModificationException;

	void entityFieldRemoved(EntityField field) throws DataSetModificationException;

	void entityFieldRenamed(EntityField field, String oldName) throws DataSetModificationException;

	/**
	 * Called when data for an entity may have been indirectly affected, such as due to the renaming of an enum value
	 *
	 * @param entityType The entity type that may have been affected
	 */
	void entityAffected(EntityType entityType);

	BetterSortedSet<MigrationSetDef> getAppliedMigrations();

	void migrationApplied(MigrationSetDef migration);

	GenericEntitySet createView(Set<String> affectedEntities, Map<String, Set<String>> requiredFields) throws DataSetModificationException;

	GenericEntitySet immutableSchema(EntityTypeSet codeTypes);
}
