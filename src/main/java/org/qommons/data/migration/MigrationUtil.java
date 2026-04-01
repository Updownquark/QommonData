package org.qommons.data.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.qommons.collect.BetterSortedSet;
import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.migration.SchemaMigration.AddEntityMigration;
import org.qommons.data.migration.SchemaMigration.AddFieldMigration;
import org.qommons.data.migration.SchemaMigration.RemoveEntityMigration;
import org.qommons.data.migration.SchemaMigration.RemoveFieldMigration;
import org.qommons.data.migration.SchemaMigration.RenameEntityMigration;
import org.qommons.data.migration.SchemaMigration.RenameFieldMigration;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.MigrationSetDef;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntitySet;

public class MigrationUtil {
	public static class MigrationDiff {
		public final BetterSortedSet<MigrationSetDef> dataSourceAppliedMigration;
		public final BetterSortedSet<MigrationSetDef> unrecognizedMigrations;
		public final BetterSortedSet<MigrationSet> unappliedMigrations;

		public MigrationDiff(BetterSortedSet<MigrationSetDef> dataSourceAppliedMigration,
			BetterSortedSet<MigrationSetDef> unrecognizedMigrations, BetterSortedSet<MigrationSet> unappliedMigrations) {
			this.dataSourceAppliedMigration = dataSourceAppliedMigration;
			this.unrecognizedMigrations = unrecognizedMigrations;
			this.unappliedMigrations = unappliedMigrations;
		}
	}

	public static class EntityTypeDiff {
		public final EntityType dataSourceType;
		public final EntityType codeType;
		public final Map<String, EntityFieldDiff> fields;

		public EntityTypeDiff(EntityType dataSourceType, EntityType codeType, Map<String, EntityFieldDiff> fields) {
			this.dataSourceType = dataSourceType;
			this.codeType = codeType;
			this.fields = fields;
		}
	}

	public static class EntityFieldDiff {
		public final EntityField dataSourceField;
		public final EntityField codeField;

		public EntityFieldDiff(EntityField dataSourceField, EntityField codeField) {
			this.dataSourceField = dataSourceField;
			this.codeField = codeField;
		}
	}

	public static MigrationDiff diffMigrations(BetterSortedSet<MigrationSetDef> dataSourceMigrations,
		BetterSortedSet<MigrationSet> codeMigrations) {
	}

	public static EntityTypeDiff diffEntityTypes(EntityTypeSet dataSourceTypes, EntityTypeSet codeTypes) {
	}

	public static void applyMigrationSet(MigratableDataSet dataSet, MigrationSet migrationSet)
		throws MigrationException, DataSetModificationException {
		Map<String, CustomMigrationComponent> migrators = new LinkedHashMap<>();
		for (ConfigurableCustomMigrator<?> m : migrationSet.getMigrators().values()) {
			try {
				migrators.put(m.getName(), m.migrator.newInstance());
			} catch (InstantiationException | IllegalAccessException | RuntimeException e) {
				throw new MigrationException("Migrator " + m + " could not be instantiated",
					m.configuration.getNamePosition().getPosition(0), e);
			}
		}
		migrators = Collections.unmodifiableMap(migrators);
		for (ConfigurableCustomMigrator<?> m : migrationSet.getMigrators().values()) {
			try {
				migrators.get(m).init(migrationSet, m.configuration, migrators,
					dataSet.createView(Collections.emptySet(), m.requiredFields));
			} catch (RuntimeException e) {
				throw new MigrationException("Migrator " + m + " could not be initialized",
					m.configuration.getNamePosition().getPosition(0), e);
			}
		}

		for (Migration migration : migrationSet.getMigrations()) {
			applyMigration(dataSet, migration, migrators);
		}
	}

	private static void applyMigration(MigratableDataSet dataSet, Migration migration, Map<String, CustomMigrationComponent> migrators)
		throws MigrationException, DataSetModificationException {
		if (migration instanceof SchemaMigration.EntityTypeMigration) {
			if (migration instanceof AddEntityMigration)
				dataSet.createEntityType(buildEntityType((AddEntityMigration) migration));
			else if (migration instanceof RemoveEntityMigration) {
				RemoveEntityMigration remove = (RemoveEntityMigration) migration;
				EntityType type = dataSet.getTypes().getEntityType(remove.entityName);
				if (type == null)
					throw new MigrationException("No such entity type '" + remove.entityName + "'", migration.getPosition());
			} else if (migration instanceof RenameEntityMigration) {
			} else if (migration instanceof AddFieldMigration) {
			} else if (migration instanceof RemoveFieldMigration) {
			} else if (migration instanceof RenameFieldMigration) {
			} else
				throw new MigrationException("Unrecognized entity migration type: " + migration.getClass().getName(),
					migration.getPosition());
		} else if (migration instanceof SchemaMigration.EnumTypeMigration) {
		} else if (migration instanceof CustomMigration) {
			GenericEntitySet view = dataSet.createView(migration.getAffectedEntities(), migration.getRequiredEntitiesAndFields());
			CustomMigration custom = (CustomMigration) migration;
			custom.migrate(migrators.get(custom.getMigrator().getName()), view);
		} else
			throw new MigrationException("Unrecognized migration type: " + migration.getClass().getName(), migration.getPosition());
	}
}
