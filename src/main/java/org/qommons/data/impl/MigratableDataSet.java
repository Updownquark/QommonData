package org.qommons.data.impl;

import java.util.Map;
import java.util.Set;

import org.qommons.collect.BetterSortedSet;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.MigrationSetDef;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntitySet;

public interface MigratableDataSet extends GenericEntitySet {
	void createEntityType(EntityType entityType) throws DataSetModificationException;

	void deleteEntityType(EntityType entityType) throws DataSetModificationException;

	void renameEntityType(EntityType entityType, String renameTo) throws DataSetModificationException;

	void addEntityField(EntityField field, Object initValue) throws DataSetModificationException;

	void removeEntityField(EntityField field) throws DataSetModificationException;

	void renameEntityField(EntityField field, String renameTo) throws DataSetModificationException;

	BetterSortedSet<MigrationSetDef> getAppliedMigrations();

	GenericEntitySet createView(Set<String> affectedEntities, Map<String, Set<String>> requiredFields) throws DataSetModificationException;
}
