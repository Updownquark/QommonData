package org.qommons.data.migration;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.qommons.ArrayUtils;
import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;
import org.qommons.data.types.modifiable.FieldMappingPrecursor;
import org.qommons.data.types.modifiable.ModifiableEntityField;
import org.qommons.data.types.modifiable.ModifiableEntityType;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.types.modifiable.ModifiableEnumType;
import org.qommons.data.types.modifiable.ModifiableEnumValue;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.ex.ExFunction;
import org.qommons.io.FilePosition;
import org.qommons.io.TextParseException;

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

	public void applyToSchema(ModifiableEntityTypeSet types) throws MigrationException {
		validate(types, null); // All sub-types must handle null validator map
	}

	public static abstract class EntityTypeMigration extends SchemaMigration {
		public final String entityName;

		protected EntityTypeMigration(MigrationSet migrationSet, FilePosition position, String entityName) {
			super(migrationSet, position);
			this.entityName = entityName;
		}

		public Set<String> getAffectedEntities() {
			return Collections.singleton(entityName);
		}

		public Map<String, Set<String>> getRequiredEntitiesAndFields() {
			return Collections.emptyMap();
		}
	}

	public static class AddEntityMigration extends EntityTypeMigration {
		public final Set<String> superTypes;
		public final Set<String> idFieldNames;
		public final Map<String, AddFieldMigration> fields;

		public AddEntityMigration(MigrationSet migrationSet, FilePosition position, String entityName, Set<String> superTypes,
			Set<String> idFieldNames, List<AddFieldMigration> fields) {
			super(migrationSet, position, entityName);
			this.superTypes = superTypes;
			this.idFieldNames = idFieldNames;
			Map<String, AddFieldMigration> myFields = new HashMap<>();
			for (AddFieldMigration field : fields)
				myFields.put(field.fieldName, field);
			this.fields = Collections.unmodifiableMap(myFields);
		}

		@Override
		public Set<String> getAffectedEntities() {
			return Collections.emptySet();
		}

		public ModifiableEntityType createEntityType(ModifiableEntityTypeSet entities,
			ExFunction<String, ModifiableEntityType, MigrationException> uncreated) throws MigrationException {
			ModifiableEntityType entityType;
			if (superTypes.isEmpty()) {
				Map<String, FieldType<?>> ids = new LinkedHashMap<>();
				for (String id : idFieldNames) {
					FieldType<?> type = MigrationUtil.parseFieldType(fields.get(id).type, entities, entityName, getPosition(), uncreated);
					if (type instanceof FieldType.ParameterizedType)
						throw new MigrationException("An entity ID field (" + id + ") cannot be a parameterized type (" + type + ")",
							getPosition());
					ids.put(id, type);
				}
				entityType = entities.createEntityType(entityName, ids, getPosition());
			} else {
				ModifiableEntityType[] superEntityTypes = new ModifiableEntityType[superTypes.size()];
				int s = 0;
				for (String sup : superTypes) {
					superEntityTypes[s] = entities.getEntityType(sup);
					if (superEntityTypes[s] == null)
						throw new MigrationException("No such entity type found for super: " + sup, getPosition());
					s++;
				}
				entityType = entities.createEntityType(entityName, superEntityTypes, getPosition());
			}
			return entityType;
		}

		private ModifiableEntityType applySchemaChange(ModifiableEntityTypeSet entities) throws MigrationException {
			ModifiableEntityType entityType = createEntityType(entities, null);
			for (AddFieldMigration field : fields.values())
				field.addField(entityType);
			return entityType;
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			applySchemaChange(entities);
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			ModifiableEntityType entityType = applySchemaChange(dataSet.getTypes());
			dataSet.entityTypeCreated(entityType);
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

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName);
			if (entityType == null)
				throw new MigrationException("No such entity type found: " + entityName, getPosition());
			else if (!entityType.getSubTypes().isEmpty())
				throw new MigrationException("Sub-types of entities must be removed before the super type", getPosition());
			boolean canMoveReferences;
			if (moveTo != null) {
				EntityType moveToType = entities.getEntityType(moveTo.targetEntity);
				if (moveToType == null)
					throw new MigrationException("Target entity type not found: " + moveTo.targetEntity, getPosition());
				if (migrators != null)
					migrators.get(moveTo.migrator.getName()).validate(entities.unmodifiableView(), getPosition());
				for (String entity : moveTo.affectedEntities) {
					if (entities.getEntityType(entity) == null)
						throw new MigrationException("Affected entity type '" + entity + "' does not exist", getPosition());
				}
				for (Map.Entry<String, Set<String>> fields : moveTo.requiredFields.entrySet()) {
					ModifiableEntityType fieldsEntity = entities.getEntityType(fields.getKey());
					if (fieldsEntity == null)
						throw new MigrationException("Required entity type '" + fields.getKey() + "' does not exist", getPosition());
					for (String field : fields.getValue()) {
						if (fieldsEntity.getField(field) == null)
							throw new MigrationException("Required field type " + fields.getKey() + "." + field + " does not exist",
								getPosition());
					}
				}

				// Ensure that any references to the given entity type can be converted to the move-to type
				canMoveReferences = entityType.getRootType() == moveToType.getRootType();
				if (canMoveReferences) {
					// In every entity that references the deleted type (and whose type allows it,
					// replace each instance with the replacement instance
					for (ModifiableEntityType referrer : entityType.getReferrers()) {
						if (referrer != entityType) {
							for (ModifiableEntityField<GenericEntity> reference : entityType.getReferences(referrer)) {
								if (!((ModifiableEntityType) reference.getType()).isAssignableFrom(moveToType))
									throw new MigrationException("Field " + reference + " cannot be migrated to " + moveToType,
										getPosition());
							}
						}
					}
				}
			} else
				canMoveReferences = false;
			if (canMoveReferences) { // Already taken care of
			} else if (entityType.getReferrers().isEmpty()) { // No references
			} else if (entityType.getReferrers().size() == 1 && entityType.getReferrers().contains(entityType)) {
				// Only self-references
			} else {
				StringBuilder str = new StringBuilder(
					"References to entity type '" + entityName + "' must be removed before the entity type:");
				for (ModifiableEntityType referrer : entityType.getReferrers()) {
					for (ModifiableEntityField<?> reference : entityType.getReferences(referrer))
						str.append("\n\t").append(referrer.getName()).append('.').append(reference.getName());
				}
				throw new MigrationException(str.toString(), getPosition());
			}
			entityType.delete(getPosition());
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws IOException, TextParseException, MigrationException, DataSetModificationException {
			ModifiableEntityType entityType = dataSet.getTypes().getEntityType(entityName);
			if (entityType == null)
				throw new MigrationException("No such entity type found: " + entityName, getPosition());
			if (moveTo != null) {
				EntityType moveToType = dataSet.getTypes().getEntityType(moveTo.targetEntity);
				if (moveToType == null)
					throw new MigrationException("Target entity type not found: " + moveTo.targetEntity, getPosition());
				Map<GenericEntity, GenericEntity> replacements = new IdentityHashMap<>();
				GenericEntitySet view = dataSet.createView(getAffectedEntities(), getRequiredEntitiesAndFields());
				EntityMoveMigrator migrator = (EntityMoveMigrator) migrators.get(moveTo.migrator.getName());
				for (GenericEntity toDelete : view.getEntities(entityName)) {
					GenericEntity replacement;
					Object[] id = toDelete.getId();
					if (view.getEntity(moveToType.getRootType().getName(), id) == null)
						replacement = view.createEntity(moveTo.targetEntity, id);
					else if (id.length == 1)
						replacement = view.createEntity(moveTo.targetEntity);
					else
						replacement = view.createEntity(moveTo.targetEntity, ArrayUtils.remove(id, id.length - 1));
					// Initialize identical fields
					for (EntityField<?> field : entityType.getFields()) {
						if (!field.isId()) {
							EntityField<?> replacementField = moveToType.getField(field.getName());
							if (replacementField != null && !replacementField.isId()
								&& replacementField.getType().isAssignableFrom(field.getType())) {
								Object oldValue = toDelete.get(field);
								replacement.set(replacementField,
									oldValue == null ? null : replacementField.getType().convert(oldValue, field.getType()));
							}
						}
						migrator.copyData(toDelete, replacement);
					}
					replacements.put(toDelete, replacement);
				}
				entityType.delete(getPosition());

				// In every entity that references the deleted type, replace each instance with the replacement instance
				for (ModifiableEntityType referrer : entityType.getReferrers()) {
					if (referrer != entityType) {
						Set<? extends ModifiableEntityField<GenericEntity>> references = entityType.getReferences(referrer);
						for (GenericEntity referrerEntity : dataSet.getEntities(referrer.getName())) {
							for (ModifiableEntityField<GenericEntity> reference : references) {
								GenericEntity referenceEntity = referrerEntity.get(reference);
								if (referenceEntity != null) {
									referrerEntity.set(reference, replacements.get(referenceEntity));
								}
							}
						}
					}
				}
				if (entityType.getRootType() == moveToType.getRootType()) {
					for (ModifiableEntityType referrer : entityType.getReferrers()) {
						if (referrer != entityType)
							for (ModifiableEntityField<GenericEntity> reference : entityType.getReferences(referrer)) {
							}
					}
				}

				dataSet.entityTypeRemoved(entityType);
			}
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

		private ModifiableEntityType applySchemaChange(ModifiableEntityTypeSet entities) throws MigrationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName);
			if (entityType == null)
				throw new MigrationException("No such entity type: '" + entityName + "'", getPosition());
			return entityType.setName(renameTo, getPosition());
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			applySchemaChange(entities);
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			ModifiableEntityType entityType = applySchemaChange(dataSet.getTypes());
			dataSet.entityTypeRenamed(entityType, entityName);
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
		public final String mappedReference;
		public final String mappedKey;
		public final String mappedIndex;
		public final String mappedSortBy;
		public final boolean ownsTargetEntity;
		public final String initValue;
		public final ConfigurableCustomMigrator<EntityFieldInitializer> initializer;
		public final Map<String, Set<String>> requiredFields;

		public AddFieldMigration(MigrationSet migrationSet, FilePosition position, String entityName, String fieldName, String type,
			String mappedReference, String mappedKey, String mappedIndex, String mappedSortBy, boolean ownsTarget, String initValue,
			ConfigurableCustomMigrator<EntityFieldInitializer> initializer, Map<String, Set<String>> requiredFields) {
			super(migrationSet, position, entityName, fieldName);
			this.type = type;
			this.mappedReference = mappedReference;
			this.mappedKey = mappedKey;
			this.mappedIndex = mappedIndex;
			this.mappedSortBy = mappedSortBy;
			this.ownsTargetEntity = ownsTarget;
			this.initValue = initValue;
			this.initializer = initializer;
			this.requiredFields = requiredFields;
		}

		@Override
		public Set<String> getAffectedEntities() {
			return Collections.singleton(entityName);
		}

		@Override
		public Map<String, Set<String>> getRequiredEntitiesAndFields() {
			return requiredFields;
		}

		private ModifiableEntityField<?> applySchemaChange(ModifiableEntityTypeSet entities) throws MigrationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName);
			if (entityType == null)
				throw new MigrationException("No such entity type '" + entityName + "'", getPosition());
			return addField(entityType);
		}

		public ModifiableEntityField<?> addField(ModifiableEntityType entityType) throws MigrationException {
			FieldType<?> realType = MigrationUtil.parseFieldType(type, entityType.getTypeSet(), null, getPosition(), null);
			FieldMappingPrecursor<?, ?> mapping = mappedReference == null ? null : new FieldMappingPrecursor<>(entityType, fieldName,
				realType, mappedReference, mappedKey, mappedIndex, mappedSortBy, ownsTargetEntity, getPosition());
			return entityType.addField(fieldName, realType, mapping, getPosition());
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			ModifiableEntityField<?> field = applySchemaChange(entities);
			if (initValue != null) {
				if (field.getType() instanceof EntityType)
					throw new MigrationException("init-value cannot be provided for entity-type fields", getPosition());
				else if (field.getType() instanceof EnumType) {
					if (!MigrationPersistence.IDENTIFIER.matcher(initValue).matches())
						throw new MigrationException("enum-typed init-value must be a literal: " + MigrationPersistence.IDENTIFIER,
							getPosition());
					else if (MigrationPersistence.RESERVED_TYPES.contains(initValue))
						throw new MigrationException(initValue + " is a reserved word", getPosition());
				} else if (field.getType() instanceof FieldType.ParameterizedType
					&& ((FieldType.ParameterizedType<?>) field.getType()).isComplex())
					throw new MigrationException("init-value cannot be provided for complex-type fields"
						+ " (parameterized types with parameterized type parameters)", getPosition());
			}
			for (Map.Entry<String, Set<String>> fields : requiredFields.entrySet()) {
				ModifiableEntityType fieldsEntity = entities.getEntityType(fields.getKey());
				if (fieldsEntity == null)
					throw new MigrationException("Required entity type '" + fields.getKey() + "' does not exist", getPosition());
				for (String reqdField : fields.getValue()) {
					if (fieldsEntity.getField(reqdField) == null)
						throw new MigrationException("Required field type " + fields.getKey() + "." + reqdField + " does not exist",
							getPosition());
				}
			}
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws IOException, TextParseException, DataSetModificationException {
			ModifiableEntityField<?> field = applySchemaChange(dataSet.getTypes());
			EntityFieldInitializer migrator = initializer == null ? null : (EntityFieldInitializer) migrators.get(initializer.getName());
			fieldAdded(field, dataSet, migrator);
		}

		private <F> void fieldAdded(ModifiableEntityField<F> field, MigratableDataSet dataSet, EntityFieldInitializer migrator)
			throws IOException, TextParseException, DataSetModificationException {
			F initialValue;
			if (initValue != null)
				initialValue = MigrationUtil.parseFieldValue(initValue, field.getType(), dataSet, this::getPosition);
			else if (field.getType() instanceof FieldType.ParameterizedType)
				initialValue = ((FieldType.ParameterizedType<F>) field.getType()).createEmptyStructure();
			else
				initialValue = null;
			dataSet.entityFieldAdded(field, initialValue);
			if (initValue != null || initializer != null) {
				GenericEntitySet view = dataSet.createView(getAffectedEntities(), requiredFields);
				for (GenericEntity entity : view.getEntities(entityName)) {
					if (migrator != null)
						entity.set(field, migrator.getInitialValue(entity));
				}
			}
		}
	}

	public static class RemoveFieldMigration extends EntityFieldMigration {
		public RemoveFieldMigration(MigrationSet migrationSet, FilePosition position, String entityName, String fieldName) {
			super(migrationSet, position, entityName, fieldName);
		}

		private ModifiableEntityField<?> applySchemaChange(ModifiableEntityTypeSet entities) throws MigrationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName);
			if (entityType == null)
				throw new MigrationException("No such entity type '" + entityName + "'", getPosition());
			ModifiableEntityField<?> field = entityType.getField(fieldName);
			if (field == null)
				throw new MigrationException("No such field " + entityName + "." + fieldName, getPosition());
			else if (field.getOwner() != entityType)
				throw new MigrationException("Field " + entityName + "." + fieldName + " is owned by super-type " + field.getOwner(),
					getPosition());
			else if (field.getMappingReference() != null)
				throw new MigrationException("Field " + field + " is referenced by mapped field: " + field.getMappingReference(),
					getPosition());
			else if (field.getIndexReference() != null)
				throw new MigrationException("Field " + field + " is referenced by mapped field: " + field.getIndexReference(),
					getPosition());
			else if (!field.getAncillaryMappingReferences().isEmpty())
				throw new MigrationException("Field " + field + " is referenced by mapped fields: " + field.getAncillaryMappingReferences(),
					getPosition());
			field.delete();
			return field;
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			applySchemaChange(entities);
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			ModifiableEntityField<?> field = applySchemaChange(dataSet.getTypes());
			dataSet.entityFieldRemoved(field);
		}
	}

	public static class RenameFieldMigration extends EntityFieldMigration {
		public final String renameTo;

		public RenameFieldMigration(MigrationSet migrationSet, FilePosition position, String entityName, String fieldName,
			String renameTo) {
			super(migrationSet, position, entityName, fieldName);
			this.renameTo = renameTo;
		}

		private ModifiableEntityField<?> applySchemaChange(ModifiableEntityTypeSet entities) throws MigrationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName);
			if (entityType == null)
				throw new MigrationException("No such entity type '" + entityName + "'", getPosition());
			ModifiableEntityField<?> field = entityType.getField(fieldName);
			if (field == null)
				throw new MigrationException("No such field " + entityName + "." + fieldName, getPosition());
			return field.setName(renameTo, getPosition());
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			applySchemaChange(entities);
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			ModifiableEntityField<?> field = applySchemaChange(dataSet.getTypes());
			dataSet.entityFieldRenamed(field, fieldName);
		}
	}

	public static abstract class EnumTypeMigration extends SchemaMigration {
		public final String enumName;

		protected EnumTypeMigration(MigrationSet migrationSet, FilePosition position, String enumName) {
			super(migrationSet, position);
			this.enumName = enumName;
		}
	}

	public static class AddEnumMigration extends EnumTypeMigration {
		public final Set<String> initialValues;

		public AddEnumMigration(MigrationSet migrationSet, FilePosition position, String enumName, Set<String> initialValues) {
			super(migrationSet, position, enumName);
			this.initialValues = initialValues;
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			ModifiableEnumType enumType = entities.createEnumType(enumName, getPosition());
			for (String value : initialValues)
				enumType.addValue(value, getPosition());
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			ModifiableEnumType enumType = dataSet.getTypes().createEnumType(enumName, getPosition());
			for (String value : initialValues)
				enumType.addValue(value, getPosition());
		}
	}

	public static class RemoveEnumMigration extends EnumTypeMigration {
		public RemoveEnumMigration(MigrationSet migrationSet, FilePosition position, String enumName) {
			super(migrationSet, position, enumName);
		}

		private void applySchemaChange(ModifiableEntityTypeSet entities) throws MigrationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName);
			if (enumType == null)
				throw new MigrationException("No such enum '" + enumName + "'", getPosition());
			for (EntityType entity : entities.getEntityTypes()) {
				for (EntityField<?> field : entity.getLocalFields()) {
					if (field.getType() == enumType)
						throw new MigrationException("Enum '" + enumName + "' is referred to by field " + field, getPosition());
				}
			}
			enumType.delete(getPosition());
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			applySchemaChange(entities);
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			applySchemaChange(dataSet.getTypes());
		}
	}

	public static class RenameEnumMigration extends EnumTypeMigration {
		public final String renameTo;

		public RenameEnumMigration(MigrationSet migrationSet, FilePosition position, String enumName, String renameTo) {
			super(migrationSet, position, enumName);
			this.renameTo = renameTo;
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName);
			if (enumType == null)
				throw new MigrationException("No such enum '" + enumName + "'", getPosition());
			enumType.setName(renameTo, getPosition());
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			ModifiableEnumType enumType = dataSet.getTypes().getEnumType(enumName);
			if (enumType == null)
				throw new MigrationException("No such enum '" + enumName + "'", getPosition());
			enumType.setName(renameTo, getPosition());
		}
	}

	public static abstract class EnumValueMigration extends EnumTypeMigration {
		public final String valueName;

		protected EnumValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName) {
			super(migrationSet, position, enumName);
			this.valueName = valueName;
		}
	}

	public static class AddValueMigration extends EnumValueMigration {
		public AddValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName) {
			super(migrationSet, position, enumName, valueName);
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName);
			if (enumType == null)
				throw new MigrationException("No such enum '" + enumName + "'", getPosition());
			enumType.addValue(valueName, getPosition());
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			dataSet.getTypes().getEnumType(enumName).addValue(valueName, getPosition());
		}
	}

	public static class RemoveValueMigration extends EnumValueMigration {
		public RemoveValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName) {
			super(migrationSet, position, enumName, valueName);
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName);
			if (enumType == null)
				throw new MigrationException("No such enum '" + enumName + "'", getPosition());
			ModifiableEnumValue value = enumType.getValue(valueName);
			if (value == null)
				throw new MigrationException("No such enum value " + value, getPosition());
			value.delete();
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws IOException, TextParseException, MigrationException, DataSetModificationException {
			ModifiableEnumType enumType = dataSet.getTypes().getEnumType(enumName);
			if (enumType == null)
				throw new MigrationException("No such enum '" + enumName + "'", getPosition());
			ModifiableEnumValue value = enumType.getValue(valueName);
			if (value == null)
				throw new MigrationException("No such enum value " + value, getPosition());
			for (EntityType entityType : enumType.getReferrers()) {
				for (GenericEntity entity : dataSet.getEntities(entityType.getName())) {
					for (EntityField<EnumValue> field : enumType.getReferences(entityType)) {
						if (entity.get(field) == value)
							throw new MigrationException("Enum value " + value + " is referred to by " + entity + "." + field.getName(),
								getPosition());
					}
				}
			}
			value.delete();
		}
	}

	public static class RenameValueMigration extends EnumValueMigration {
		public final String renameTo;

		public RenameValueMigration(MigrationSet migrationSet, FilePosition position, String enumName, String valueName, String renameTo) {
			super(migrationSet, position, enumName, valueName);
			this.renameTo = renameTo;
		}

		private ModifiableEnumValue applySchemaChange(ModifiableEntityTypeSet entities) throws MigrationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName);
			if (enumType == null)
				throw new MigrationException("No such enum '" + enumName + "'", getPosition());
			ModifiableEnumValue value = enumType.getValue(valueName);
			if (value == null)
				throw new MigrationException("No such enum value " + value, getPosition());
			return value.setName(renameTo, getPosition());
		}

		@Override
		public void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException {
			applySchemaChange(entities);
		}

		@Override
		public void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
			throws MigrationException, DataSetModificationException {
			EnumValue value = applySchemaChange(dataSet.getTypes());
			validate(dataSet.getTypes(), migrators);
			for (EntityType entityType : value.getType().getReferrers()) {
				dataSet.entityAffected(entityType);
			}
		}
	}
}
