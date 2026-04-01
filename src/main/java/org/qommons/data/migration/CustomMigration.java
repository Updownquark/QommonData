package org.qommons.data.migration;

import java.util.Map;
import java.util.Set;

import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.io.FilePosition;

public abstract class CustomMigration implements Migration {
	private final MigrationSet theMigrationSet;
	private final FilePosition thePosition;
	private final Set<String> theAffectedEntities;
	private final Map<String, Set<String>> theRequiredFields;
	private final ConfigurableCustomMigrator<?> theMigrator;

	protected CustomMigration(MigrationSet migrationSet, FilePosition position, Set<String> affectedEntities,
		Map<String, Set<String>> requiredFields, ConfigurableCustomMigrator<?> migrator) {
		theMigrationSet = migrationSet;
		thePosition = position;
		theAffectedEntities = affectedEntities;
		theRequiredFields = requiredFields;
		theMigrator = migrator;
	}

	@Override
	public MigrationSet getMigrationSet() {
		return theMigrationSet;
	}

	@Override
	public FilePosition getPosition() {
		return thePosition;
	}

	@Override
	public Set<String> getAffectedEntities() {
		return theAffectedEntities;
	}

	@Override
	public Map<String, Set<String>> getRequiredEntitiesAndFields() {
		return theRequiredFields;
	}

	public ConfigurableCustomMigrator<?> getMigrator() {
		return theMigrator;
	}

	public abstract void migrate(CustomMigrationComponent migrator, GenericEntitySet entitySet) throws MigrationException;

	public static class ForEachMigration extends CustomMigration {
		public final String targetEntity;

		public ForEachMigration(MigrationSet migrationSet, FilePosition position, String targetEntity, Set<String> affectedEntities,
			Map<String, Set<String>> requiredFields, ConfigurableCustomMigrator<SingleEntityCustomMigrator> migrator) {
			super(migrationSet, position, affectedEntities, requiredFields, migrator);
			this.targetEntity = targetEntity;
		}

		@Override
		public ConfigurableCustomMigrator<SingleEntityCustomMigrator> getMigrator() {
			return (ConfigurableCustomMigrator<SingleEntityCustomMigrator>) super.getMigrator();
		}

		@Override
		public void migrate(CustomMigrationComponent migrator, GenericEntitySet entitySet) throws MigrationException {
			for (GenericEntity entity : entitySet.getEntities(targetEntity))
				((SingleEntityCustomMigrator) migrator).handle(entity, entitySet);
		}
	}

	public static class WholeSetCustomMigration extends CustomMigration {
		public WholeSetCustomMigration(MigrationSet migrationSet, FilePosition position, Set<String> affectedEntities,
			Map<String, Set<String>> requiredFields, ConfigurableCustomMigrator<WholeSetCustomMigrator> migrator) {
			super(migrationSet, position, affectedEntities, requiredFields, migrator);
		}

		@Override
		public ConfigurableCustomMigrator<WholeSetCustomMigrator> getMigrator() {
			return (ConfigurableCustomMigrator<WholeSetCustomMigrator>) super.getMigrator();
		}

		@Override
		public void migrate(CustomMigrationComponent migrator, GenericEntitySet entitySet) throws MigrationException {
			((WholeSetCustomMigrator) migrator).migrate(entitySet);
		}
	}
}
