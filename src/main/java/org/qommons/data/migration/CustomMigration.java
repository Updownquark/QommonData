package org.qommons.data.migration;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.io.FilePosition;
import org.qommons.io.TextParseException;

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

	public Set<String> getAffectedEntities() {
		return theAffectedEntities;
	}

	public Map<String, Set<String>> getRequiredEntitiesAndFields() {
		return theRequiredFields;
	}

	public ConfigurableCustomMigrator<?> getMigrator() {
		return theMigrator;
	}

	@Override
	public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
		CustomMigrationComponent migrator = migrators.get(theMigrator.getName());
		migrator.validate(entities.unmodifiableView(), thePosition);
	}

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
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws IOException, TextParseException, MigrationException, DataSetModificationException {
			GenericEntitySet view = dataSet.createView(getAffectedEntities(), getRequiredEntitiesAndFields());
			SingleEntityCustomMigrator migrator = (SingleEntityCustomMigrator) migrators.get(getMigrator().getName());
			for (GenericEntity entity : view.getEntities(targetEntity))
				migrator.handle(entity, view);
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
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws IOException, TextParseException, MigrationException, DataSetModificationException {
			GenericEntitySet view = dataSet.createView(getAffectedEntities(), getRequiredEntitiesAndFields());
			WholeSetCustomMigrator migrator = (WholeSetCustomMigrator) migrators.get(getMigrator().getName());
			migrator.migrate(view);
		}
	}
}
