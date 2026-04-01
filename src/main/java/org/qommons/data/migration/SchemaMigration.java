package org.qommons.data.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.qommons.io.FilePosition;

public abstract class SchemaMigration implements Migration {
	private final MigrationSet theMigrationSet;
	private final FilePosition thePosition;

	protected SchemaMigration(MigrationSet migrationSet, FilePosition position) {
		theMigrationSet = migrationSet;
		thePosition = position;
	}

	@Override
	public MigrationSet getMigrationSet() {
		return theMigrationSet;
	}

	@Override
	public FilePosition getPosition() {
		return thePosition;
	}

	public static abstract class EntityTypeMigration extends SchemaMigration {
		public final String entityName;

		protected EntityTypeMigration(MigrationSet migrationSet, FilePosition position, String entityName) {
			super(migrationSet, position);
			this.entityName = entityName;
		}

		@Override
		public Set<String> getAffectedEntities() {
			return Collections.singleton(entityName);
		}

		@Override
		public Map<String, Set<String>> getRequiredEntitiesAndFields() {
			return Collections.emptyMap();
		}
	}

	public static class AddEntityMigration extends EntityTypeMigration {
		public final String superType;
		public final Set<String> idFieldNames;
		public final List<AddFieldMigration> fields;

		public AddEntityMigration(MigrationSet migrationSet, FilePosition position, String entityName, String superType,
			Set<String> idFieldNames, List<AddFieldMigration> fields) {
			super(migrationSet, position, entityName);
			this.superType = superType;
			this.idFieldNames = idFieldNames;
			this.fields = fields;
		}

		@Override
		public Set<String> getAffectedEntities() {
			return Collections.emptySet();
		}
	}

	public static class RemoveEntityMigration extends EntityTypeMigration {
		public final EntityMove moveTo;

		public RemoveEntityMigration(MigrationSet migrationSet, FilePosition position, String entityName, EntityMove moveTo) {
			super(migrationSet, position, entityName);
			this.moveTo = moveTo;
		}

		@Override
		public Set<String> getAffectedEntities() {
			if (moveTo == null)
				return super.getAffectedEntities();
			Set<String> affected = new LinkedHashSet<>(getAffectedEntities());
			affected.addAll(moveTo.affectedEntities);
			return affected;
		}

		@Override
		public Map<String, Set<String>> getRequiredEntitiesAndFields() {
			if (moveTo == null)
				return super.getRequiredEntitiesAndFields();
			Map<String, Set<String>> fields = new LinkedHashMap<>(super.getRequiredEntitiesAndFields());
			for (Map.Entry<String, Set<String>> field : moveTo.requiredFields.entrySet())
				fields.computeIfAbsent(field.getKey(), __ -> new LinkedHashSet<>()).addAll(field.getValue());
			return fields;
		}
	}

	public static class EntityMove {
		public final String targetEntity;
		public final Set<String> affectedEntities;
		public final Map<String, Set<String>> requiredFields;
		public final ConfigurableCustomMigrator<EntityMoveMigrator> migrator;

		public EntityMove(String targetEntity, Set<String> affectedEntities, Map<String, Set<String>> requiredFields,
			ConfigurableCustomMigrator<EntityMoveMigrator> migrator) {
			this.targetEntity = targetEntity;
			this.affectedEntities = affectedEntities;
			this.requiredFields = requiredFields;
			this.migrator = migrator;
		}
	}

	public static class RenameEntityMigration extends EntityTypeMigration {
		public final String renameTo;

		public RenameEntityMigration(MigrationSet migrationSet, FilePosition position, String entityName, String renameTo) {
			super(migrationSet, position, entityName);
			this.renameTo = renameTo;
		}
	}

	public static abstract class EntityFieldMigration extends EntityTypeMigration {
		public final String fieldName;

		protected EntityFieldMigration(MigrationSet migrationSet, FilePosition position, String entityName, String fieldName) {
			super(migrationSet, position, entityName);
			this.fieldName = fieldName;
		}

		@Override
		public Map<String, Set<String>> getRequiredEntitiesAndFields() {
			return Collections.singletonMap(entityName, Collections.singleton(fieldName));
		}
	}

	public static class AddFieldMigration extends EntityFieldMigration {
		public final String type;
		public final String initValue;
		public final ConfigurableCustomMigrator<EntityFieldInitializer> initializer;

		public AddFieldMigration(MigrationSet migrationSet, FilePosition position, String entityName, String fieldName, String type,
			String initValue, ConfigurableCustomMigrator<EntityFieldInitializer> initializer) {
			super(migrationSet, position, entityName, fieldName);
			this.type = type;
			this.initValue = initValue;
			this.initializer = initializer;
		}

		@Override
		public Map<String, Set<String>> getRequiredEntitiesAndFields() {
			return Collections.emptyMap();
		}
	}

	public static class RemoveFieldMigration extends EntityFieldMigration {
		public RemoveFieldMigration(MigrationSet migrationSet, FilePosition position, String entityName, String fieldName) {
			super(migrationSet, position, entityName, fieldName);
		}
	}

	public static class RenameFieldMigration extends EntityFieldMigration {
		public final String renameTo;

		public RenameFieldMigration(MigrationSet migrationSet, FilePosition position, String entityName, String fieldName,
			String renameTo) {
			super(migrationSet, position, entityName, fieldName);
			this.renameTo = renameTo;
		}
	}

	public static abstract class EnumTypeMigration extends SchemaMigration {
		public final String enumName;

		protected EnumTypeMigration(MigrationSet migrationSet, FilePosition position, String enumName) {
			super(migrationSet, position);
			this.enumName = enumName;
		}

		@Override
		public Set<String> getAffectedEntities() {
			return Collections.emptySet();
		}

		@Override
		public Map<String, Set<String>> getRequiredEntitiesAndFields() {
			return Collections.emptyMap();
		}
	}

	public static class AddEnumMigration extends EntityTypeMigration {
		public AddEnumMigration(MigrationSet migrationSet, FilePosition position, String enumName) {
			super(migrationSet, position, enumName);
		}
	}

	public static class RemoveEnumMigration extends EntityTypeMigration {
		public RemoveEnumMigration(MigrationSet migrationSet, FilePosition position, String enumName) {
			super(migrationSet, position, enumName);
		}
	}

	public static class RenameEnumMigration extends EntityTypeMigration {
		public final String renameTo;

		public RenameEnumMigration(MigrationSet migrationSet, FilePosition position, String enumName, String renameTo) {
			super(migrationSet, position, enumName);
			this.renameTo = renameTo;
		}
	}

	public static abstract class EnumValueMigration extends EntityTypeMigration {
		public final String valueName;

		protected EnumValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName) {
			super(migrationSet, position, enumName);
			this.valueName = valueName;
		}
	}

	public static class AddValueMigration extends EntityFieldMigration {
		public AddValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName) {
			super(migrationSet, position, enumName, valueName);
		}
	}

	public static class RemoveValueMigration extends EntityFieldMigration {
		public RemoveValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName) {
			super(migrationSet, position, enumName, valueName);
		}
	}

	public static class RenameValueMigration extends EntityFieldMigration {
		public final String renameTo;

		public RenameValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName, String renameTo) {
			super(migrationSet, position, enumName, valueName);
			this.renameTo = renameTo;
		}
	}
}
