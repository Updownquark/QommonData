package org.qommons.data.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.qommons.ArrayUtils;
import org.qommons.BiTuple;
import org.qommons.QommonsUtils;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigInterpreterCore;
import org.qommons.config.QonfigInterpreterCore.CoreSession;
import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
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
import org.qommons.ex.ExFunction;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.io.PositionedContent;
import org.qommons.io.TextParseException;

public abstract class SchemaMigration implements Migration {
	public static final Set<String> RESERVED_TYPES = QommonsUtils.unmodifiableDistinctCopy("boolean", "char", "byte", "short", "int",
		"long", "float", "double", "String");

	private final MigrationSet theMigrationSet;
	private final LocatedPositionedContent thePosition;

	protected SchemaMigration(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		theMigrationSet = (MigrationSet) session.get(MIGRATION_SET_KEY);
		thePosition = session.getElement().getFilePosition();
	}

	@Override
	public MigrationSet getMigrationSet() {
		return theMigrationSet;
	}

	@Override
	public LocatedPositionedContent getPosition() {
		return thePosition;
	}

	public abstract Object applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException;

	public static abstract class EntityTypeMigration extends SchemaMigration {
		public final LocatedPositionedContent entityName;

		protected EntityTypeMigration(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
			super(session);
			if (this instanceof AddFieldMigration) {
				AddEntityMigration addEntity = (AddEntityMigration) session.get(AddEntityMigration.ADDING_ENTITY);
				if (addEntity != null)
					entityName = addEntity.entityName;
				else
					entityName = session.attributes().get("entity").getLocatedContent();
			} else
				entityName = session.attributes().get("entity").getLocatedContent();
			if (RESERVED_TYPES.contains(entityName))
				throw new QonfigInterpretationException("'" + entityName + "' is a reserved type name", entityName);
		}
	}

	public static class AddEntityMigration extends EntityTypeMigration {
		public static final String ADDING_ENTITY = "Adding Entity";

		public final Set<LocatedPositionedContent> superTypes;
		public final Set<LocatedPositionedContent> idFieldNames;
		public final Map<String, AddFieldMigration> fields;

		AddEntityMigration(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
			super(session);
			LocatedPositionedContent superTypeStr = session.attributes().get("super").getLocatedContent();
			LocatedPositionedContent idFieldsStr = session.attributes().get("id").getLocatedContent();
			if (superTypeStr != null) {
				if (idFieldsStr != null)
					throw new QonfigInterpretationException("Entity types with a super type must inherit their super type's id",
						idFieldsStr);
				Set<LocatedPositionedContent> supers = new LinkedHashSet<>();
				PositionedContent.split(superTypeStr, ',', supers::add);
				superTypes = Collections.unmodifiableSet(supers);
			} else {
				superTypes = Collections.emptySet();
				if (idFieldsStr == null)
					throw new QonfigInterpretationException("New entity types must have either a super type or id fields",
						session.getElement().getFilePosition());
			}

			Map<String, AddFieldMigration> entityFields = new LinkedHashMap<>();
			session.put(ADDING_ENTITY, this);
			for (QonfigInterpreterCore.CoreSession fieldSession : session.forChildren("field")) {
				AddFieldMigration field = fieldSession.interpret(AddFieldMigration.class);
				entityFields.put(field.fieldName.toString(), field);
			}
			fields = Collections.unmodifiableMap(entityFields);
			if (idFieldsStr != null) {
				Set<LocatedPositionedContent> idFields = new LinkedHashSet<>();
				int idCount = PositionedContent.split(idFieldsStr, ',', id -> {
					AddFieldMigration idField = entityFields.get(id.toString());
					if (idField == null)
						throw new QonfigInterpretationException("ID field '" + id + "' not declared", id);
					else if (idField.mapping != null)
						throw new QonfigInterpretationException("Mapped field '" + id + "' cannot be used as an ID field", id);
					idFields.add(id);
				});
				if (idCount == 0)
					throw new QonfigInterpretationException("add-entity.id cannot be empty", idFieldsStr);
				idFieldNames = QommonsUtils.unmodifiableDistinctCopy(idFields);
			} else
				idFieldNames = Collections.emptySet();

			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		public ModifiableEntityType createEntityType(ModifiableEntityTypeSet entities,
			ExFunction<String, ModifiableEntityType, QonfigInterpretationException> uncreated) throws QonfigInterpretationException {
			ModifiableEntityType entityType;
			if (superTypes.isEmpty()) {
				Map<LocatedPositionedContent, FieldType<?>> ids = new LinkedHashMap<>();
				for (LocatedPositionedContent id : idFieldNames) {
					String fieldName = id.toString();
					FieldType<?> type = MigrationUtil.parseFieldType(fields.get(fieldName).type, entities, entityName, uncreated);
					if (type instanceof FieldType.ParameterizedType)
						throw new QonfigInterpretationException(
							"An entity ID field (" + fieldName + ") cannot be a parameterized type (" + type + ")", id);
					ids.put(id, type);
				}
				entityType = entities.createEntityType(entityName, ids);
			} else {
				ModifiableEntityType[] superEntityTypes = new ModifiableEntityType[superTypes.size()];
				int s = 0;
				for (LocatedPositionedContent sup : superTypes) {
					String superName = sup.toString();
					superEntityTypes[s] = entities.getEntityType(superName);
					if (superEntityTypes[s] == null)
						throw new QonfigInterpretationException("No such entity type found for super: " + superName, sup);
					s++;
				}
				entityType = entities.createEntityType(entityName, superEntityTypes);
			}
			return entityType;
		}

		@Override
		public ModifiableEntityType applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEntityType entityType = createEntityType(entities, null);
			for (AddFieldMigration field : fields.values()) {
				if (!idFieldNames.contains(field.fieldName))
					field.addField(entityType, true);
			}
			return entityType;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session)
			throws IOException, TextParseException, DataSetModificationException {
			ModifiableEntityType entityType = applySchemaChange(dataSet.getTypes());
			dataSet.entityTypeCreated(entityType);
		}
	}

	public static class RemoveEntityMigration extends EntityTypeMigration {
		public final EntityMoveMigrator moveTo;

		public RemoveEntityMigration(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
			super(session);
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			ModifiableEntityType entity = history.getTypeSet().getEntityType(entityName.toString());
			if (entity == null)
				throw new QonfigInterpretationException("No such entity type '" + entityName + "'", entityName);
			QonfigInterpreterCore.CoreSession moveToSession = session.forChildren("moveTo").peekFirst();
			if (moveToSession != null) {
				moveToSession.put(EntityMoveMigrator.SOURCE_ENTITY, entity);
				moveTo = moveToSession.interpret(EntityMoveMigrator.class);
			} else
				moveTo = null;
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEntityType applySchemaChange(ModifiableEntityTypeSet types) throws QonfigInterpretationException {
			ModifiableEntityType entity = types.getEntityType(entityName.toString());
			if (entity == null)
				throw new QonfigInterpretationException("No such entity type '" + entityName + "'", entityName);

			if (moveTo != null) {
				boolean canMoveReferences;
				EntityType moveToType = types.getEntityType(moveTo.getTargetEntity()); // Assume the move-to validated itself

				// Ensure that any references to the given entity type can be converted to the move-to type
				canMoveReferences = entity.getRootType() == moveToType.getRootType();
				if (canMoveReferences) {
					// In every entity that references the deleted type (and whose type allows it,
					// replace each instance with the replacement instance
					for (ModifiableEntityType referrer : entity.getReferrers()) {
						if (referrer != entity) {
							for (ModifiableEntityField<GenericEntity> reference : entity.getReferences(referrer)) {
								if (!((ModifiableEntityType) reference.getType()).isAssignableFrom(moveToType))
									throw new QonfigInterpretationException("Field " + reference + " cannot be migrated to " + moveToType,
										getPosition());
							}
						}
					}
				}
				if (canMoveReferences) { // Already taken care of
				} else if (entity.getReferrers().isEmpty()) { // No references
				} else if (entity.getReferrers().size() == 1 && entity.getReferrers().contains(entity)) {
					// Only self-references
				} else {
					StringBuilder str = new StringBuilder(
						"References to entity type '" + entityName + "' must be removed before the entity type:");
					for (ModifiableEntityType referrer : entity.getReferrers()) {
						for (ModifiableEntityField<?> reference : entity.getReferences(referrer))
							str.append("\n\t").append(referrer.getName()).append('.').append(reference.getName());
					}
					throw new QonfigInterpretationException(str.toString(), getPosition());
				}
			}

			entity.delete(entityName);
			return entity;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session)
			throws IOException, TextParseException, DataSetModificationException {
			ModifiableEntityType entityType = dataSet.getTypes().getEntityType(entityName.toString());
			if (entityType == null)
				throw new DataSetModificationException("No such entity type found: " + entityName);
			if (moveTo != null) {
				String target = moveTo.getTargetEntity();
				EntityType moveToType = dataSet.getTypes().getEntityType(target);
				if (moveToType == null)
					throw new DataSetModificationException("Target entity type not found: " + target);
				moveTo.prepare(entityType, moveToType);
				Map<GenericEntity, GenericEntity> replacements = new IdentityHashMap<>();
				for (GenericEntity toDelete : dataSet.getEntities(entityName.toString())) {
					GenericEntity replacement = moveTo.getOrCreateReplacement(toDelete, moveToType);
					moveTo.copyData(toDelete, replacement);
					replacements.put(toDelete, replacement);
				}

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

				entityType.delete(getPosition());
				dataSet.entityTypeRemoved(entityType);
			}
		}
	}

	public static class DefaultMoveTo implements EntityMoveMigrator {
		private final LocatedPositionedContent theTargetEntity;
		private final List<MapField> theMappedFields;
		private final MapField[] theMappedIds;

		public DefaultMoveTo(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
			theTargetEntity = session.attributes().get("target").getLocatedContent();
			EntityType targetType = SchemaHistory.get(session).getTypeSet().getEntityType(theTargetEntity.toString());
			if (targetType == null)
				throw new QonfigInterpretationException("No such entity type '" + theTargetEntity + "'", theTargetEntity);
			session.put(SingleEntityCustomMigrator.AFFECTED_ENTITY, targetType);
			theMappedFields = new ArrayList<>();
			MapField[] ids = new MapField[targetType.getIdFields().size()];
			EntityType sourceType = (EntityType) session.get(EntityMoveMigrator.SOURCE_ENTITY);
			Set<String> sourceFieldNames = new HashSet<>();
			Set<String> targetFieldNames = new HashSet<>();
			for (QonfigInterpreterCore.CoreSession mappedField : session.forChildren("mapped-fields")) {
				MapField field = mappedField.interpret(MapField.class);
				if (!field.from.hasPath())
					sourceFieldNames.add(field.from.getTargetFieldName());
				if (!field.to.hasPath()) {
					targetFieldNames.add(field.to.getTargetFieldName());
					int idIndex = targetType.getIdFields().indexOf(targetType.getField(field.to.getTargetFieldName()));
					if (idIndex >= 0)
						ids[idIndex] = field;
				} else
					theMappedFields.add(field);
			}
			for (EntityField<?> sourceField : sourceType.getFields()) {
				if (sourceFieldNames.contains(sourceField.getName()))
					continue;
				EntityField<?> targetField;
				if (sourceField.getOwner().isAssignableFrom(targetType))
					targetField = sourceField;
				else {
					targetField = targetType.getField(sourceField.getName());
					if (targetField == null) {
						session.reporting().warn("No mapping available for field " + sourceField);
						continue;
					}
				}
				if (!targetFieldNames.add(targetField.getName()))
					continue;
				try {
					MapField field = new MapField(new FieldGetter.Simple<>(new FieldPath<>(null, (EntityField<Object>) sourceField)),
						FieldSetter.parse(targetType, (FieldType<Object>) sourceField.getType(), sourceField.getName(),
							LocatedPositionedContent.of(null, sourceField.getName())));
					if (targetField.isId())
						ids[targetType.getIdFields().indexOf(targetField)] = field;
					else
						theMappedFields.add(field);
				} catch (QonfigInterpretationException e) {
					// This is not fatal, it just means this source field can't be mapped and the information will be lost
					session.reporting().warn("No mapping available for field " + sourceField, e);
				}
			}
			for (int i = 0; i < ids.length; i++) {
				if (ids[i] == null) {
					if (i == ids.length - 1 // It may be ok for the last ID field to be null--the entity set can auto-populate it
						&& MigrationUtil.isIncrementable(targetType.getIdFields().get(i).getType())) {
						ids = ArrayUtils.remove(ids, i);
					} else
						throw new QonfigInterpretationException(
							"No mapping available for required ID field " + targetType.getIdFields().get(i),
							session.getElement().getFilePosition());
				}
			}
			theMappedIds = ids;
		}

		@Override
		public String getTargetEntity() {
			return theTargetEntity.toString();
		}

		@Override
		public void prepare(EntityType sourceType, EntityType targetType) throws QonfigInterpretationException {
			for (MapField field : theMappedIds) {
				field.from.prepare(sourceType);
				field.to.prepare(targetType);
			}
			for (MapField field : theMappedFields) {
				field.from.prepare(sourceType);
				field.to.prepare(targetType);
			}
		}

		@Override
		public GenericEntity getOrCreateReplacement(GenericEntity sourceEntity, EntityType targetType)
			throws QonfigInterpretationException {
			Object[] id = new Object[theMappedIds.length];
			for (int i = 0; i < id.length; i++)
				id[i] = theMappedIds[i].from.apply(sourceEntity);
			if (theMappedIds.length == targetType.getIdFields().size()) {
				GenericEntity found;
				try {
					found = sourceEntity.getEntitySet().getEntity(targetType.getName(), id);
				} catch (IOException e) {
					throw new QonfigInterpretationException("Could not read " + targetType + " entities", theTargetEntity, e);
				}
				if (found == null)
					found = sourceEntity.getEntitySet().createEntity(targetType.getName(), id);
				return found;
			} else
				return sourceEntity.getEntitySet().createEntity(targetType.getName(), id);
		}

		@Override
		public void copyData(GenericEntity oldEntity, GenericEntity newEntity) {
			for (MapField field : theMappedFields) {
				Object value = field.from.apply(oldEntity);
				field.to.accept(newEntity, value);
			}
		}
	}

	public static class MapField {
		public final FieldGetter<Object> from;
		public final FieldSetter<Object, Object> to;

		public MapField(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
			EntityType sourceType = (EntityType) session.get(EntityMoveMigrator.SOURCE_ENTITY);
			EntityType targetType = (EntityType) session.get(EntityMoveMigrator.TARGET_ENTITY);
			if (targetType == null)
				throw new QonfigInterpretationException("Expected '" + SingleEntityCustomMigrator.AFFECTED_ENTITY + "' session property",
					session.getElement().getFilePosition());
			LocatedPositionedContent fromText = session.attributes().get("from").getLocatedContent();
			BiTuple<FieldType<Object>, FieldGetter<Object>> fromField = FieldGetter.parse(sourceType, fromText);
			from = fromField.getValue2();
			to = FieldSetter.parse(targetType, fromField.getValue1(), fromText, session.attributes().get("to").getLocatedContent());
		}

		public MapField(FieldGetter<Object> from, FieldSetter<Object, Object> to) {
			this.from = from;
			this.to = to;
		}
	}

	public static class RenameEntityMigration extends EntityTypeMigration {
		public final LocatedPositionedContent renameTo;

		public RenameEntityMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			renameTo = session.attributes().get("rename-to").getLocatedContent();
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEntityType applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName.toString());
			if (entityType == null)
				throw new QonfigInterpretationException("No such entity type: '" + entityName + "'", entityName);
			return entityType.setName(renameTo);
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			ModifiableEntityType entityType;
			try {
				entityType = applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
			dataSet.entityTypeRenamed(entityType, entityName.toString());
		}
	}

	public static abstract class EntityFieldMigration extends EntityTypeMigration {
		public final LocatedPositionedContent fieldName;

		protected EntityFieldMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			fieldName = session.attributes().get("field").getLocatedContent();
		}
	}

	public static class AddFieldMigration extends EntityFieldMigration {
		public final LocatedPositionedContent type;
		public final ConfiguredFieldMapping mapping;
		public final LocatedPositionedContent initValue;
		public final EntityFieldInitializer initWith;

		public AddFieldMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);

			type = session.attributes().get("type").getLocatedContent();
			CoreSession mapped = session.forChildren("mapping").peekFirst();
			mapping = mapped == null ? null : mapped.interpret(ConfiguredFieldMapping.class);
			initValue = session.attributes().get("init-value").getLocatedContent();
			CoreSession initWithSession = session.forChildren("init-with").peekFirst();
			initWith = initWithSession == null ? null : initWithSession.interpret(EntityFieldInitializer.class);

			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			// If we're part of an add-entity migrator, we need to wait
			if (history != null && session.get(AddEntityMigration.ADDING_ENTITY) == null) {
				applySchemaChange(history.getTypeSet());
			}
		}

		@Override
		public ModifiableEntityField<?> applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEntityType entity = entities.getEntityType(entityName.toString());
			if (entity == null)
				throw new QonfigInterpretationException("No such entity '" + entityName + "'", entityName);
			return addField(entity, true);
		}

		public ModifiableEntityField<?> addField(ModifiableEntityType entity, boolean checkValue) throws QonfigInterpretationException {
			FieldType<?> fieldType = MigrationUtil.parseFieldType(type, entity.getTypeSet(), entityName, null);
			if (initValue != null) {
				if (fieldType instanceof EntityType)
					throw new QonfigInterpretationException("init-value cannot be provided for entity-type fields", initValue);
				else if (fieldType instanceof FieldType.ParameterizedType && ((FieldType.ParameterizedType<?>) fieldType).isComplex())
					throw new QonfigInterpretationException(
						"init-value is not supported for complex-type fields" + " (parameterized types with parameterized type parameters)",
						initValue);
				if (checkValue)
					MigrationUtil.parseFieldValue(initValue, fieldType, null, initValue::getPosition);
			}
			FieldMappingPrecursor<?, ?> mapped = mapping == null ? null : mapping.createMapping(entity, fieldName, fieldType);
			ModifiableEntityField<?> field = entity.addField(fieldName, fieldType, mapped);
			if (checkValue && initWith != null)
				initWith.validate(this, field);
			return field;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session)
			throws IOException, TextParseException, DataSetModificationException {
			ModifiableEntityType entity = dataSet.getTypes().getEntityType(entityName.toString());
			if (entity == null)
				throw new DataSetModificationException("No such entity type '" + entityName + "'");
			ModifiableEntityField<?> field = addField(entity, false);
			fieldAdded(field, dataSet);
		}

		private <F> void fieldAdded(ModifiableEntityField<F> field, MigratableDataSet dataSet)
			throws IOException, TextParseException, DataSetModificationException {
			F initialValue;
			if (initValue != null)
				initialValue = MigrationUtil.parseFieldValue(initValue, field.getType(), dataSet, initValue::getPosition);
			else if (field.getType() instanceof FieldType.ParameterizedType)
				initialValue = ((FieldType.ParameterizedType<F>) field.getType()).createEmptyStructure();
			else
				initialValue = null;
			dataSet.entityFieldAdded(field, initialValue);
			if (initWith != null) {
				for (GenericEntity entity : dataSet.getEntities(entityName.toString())) {
					entity.set(field, initWith.getInitialValue(entity));
				}
			}
		}
	}

	public static class ConfiguredFieldMapping {
		public final LocatedPositionedContent mappedReference;
		public final LocatedPositionedContent mappedKey;
		public final LocatedPositionedContent mappedIndex;
		public final LocatedPositionedContent mappedSortBy;
		public final boolean ownsTargetEntity;

		public ConfiguredFieldMapping(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
			mappedReference = session.attributes().get("by").getLocatedContent();
			mappedKey = session.attributes().get("key").getLocatedContent();
			mappedIndex = session.attributes().get("index").getLocatedContent();
			mappedSortBy = session.attributes().get("sort-by").getLocatedContent();
			ownsTargetEntity = session.getAttribute("owns-target", boolean.class);
		}

		public FieldMappingPrecursor<?, ?> createMapping(ModifiableEntityType entity, LocatedPositionedContent parentFieldName,
			FieldType<?> type) throws QonfigInterpretationException {
			return new FieldMappingPrecursor<>(entity, parentFieldName, type, mappedReference, mappedKey, mappedIndex, mappedSortBy,
				ownsTargetEntity);
		}
	}

	public static class RemoveFieldMigration extends EntityFieldMigration {
		public RemoveFieldMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEntityField<?> applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName.toString());
			if (entityType == null)
				throw new QonfigInterpretationException("No such entity type '" + entityName + "'", entityName);
			ModifiableEntityField<?> field = entityType.getField(fieldName.toString());
			if (field == null)
				throw new QonfigInterpretationException("No such field " + entityName + "." + fieldName, fieldName);
			else if (field.getOwner() != entityType)
				throw new QonfigInterpretationException(
					"Field " + entityName + "." + fieldName + " is owned by super-type " + field.getOwner(), fieldName);
			else if (field.getMappingReference() != null)
				throw new QonfigInterpretationException("Field " + field + " is referenced by mapped field: " + field.getMappingReference(),
					fieldName);
			else if (field.getIndexReference() != null)
				throw new QonfigInterpretationException("Field " + field + " is referenced by mapped field: " + field.getIndexReference(),
					fieldName);
			else if (!field.getAncillaryMappingReferences().isEmpty())
				throw new QonfigInterpretationException(
					"Field " + field + " is referenced by mapped fields: " + field.getAncillaryMappingReferences(), fieldName);
			field.delete();
			return field;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			ModifiableEntityField<?> field;
			try {
				field = applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
			dataSet.entityFieldRemoved(field);
		}
	}

	public static class RenameFieldMigration extends EntityFieldMigration {
		public final LocatedPositionedContent renameTo;

		public RenameFieldMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			renameTo = session.attributes().get("rename-to").getLocatedContent();
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEntityField<?> applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEntityType entityType = entities.getEntityType(entityName.toString());
			if (entityType == null)
				throw new QonfigInterpretationException("No such entity type '" + entityName + "'", entityName);
			ModifiableEntityField<?> field = entityType.getField(fieldName.toString());
			if (field == null)
				throw new QonfigInterpretationException("No such field " + entityName + "." + fieldName, fieldName);
			return field.setName(renameTo);
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			ModifiableEntityField<?> field;
			try {
				field = applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
			dataSet.entityFieldRenamed(field, fieldName.toString());
		}
	}

	public static abstract class EnumTypeMigration extends SchemaMigration {
		public final LocatedPositionedContent enumName;

		protected EnumTypeMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			enumName = session.attributes().get("enum").getLocatedContent();
		}
	}

	public static class AddEnumMigration extends EnumTypeMigration {
		public final Set<LocatedPositionedContent> initialValues;

		public AddEnumMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			Set<LocatedPositionedContent> values = new LinkedHashSet<>();
			for (CoreSession value : session.forChildren("value"))
				values.add(value.attributes().get("value").getLocatedContent());
			initialValues = Collections.unmodifiableSet(values);
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEnumType applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEnumType enumType = entities.createEnumType(enumName);
			for (LocatedPositionedContent value : initialValues)
				enumType.addValue(value);
			return enumType;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			try {
				applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
		}
	}

	public static class RemoveEnumMigration extends EnumTypeMigration {
		public RemoveEnumMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEnumType applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName.toString());
			if (enumType == null)
				throw new QonfigInterpretationException("No such enum '" + enumName + "'", enumName);
			for (EntityType entity : entities.getEntityTypes()) {
				for (EntityField<?> field : entity.getLocalFields()) {
					if (field.getType() == enumType)
						throw new QonfigInterpretationException("Enum '" + enumName + "' is referred to by field " + field, getPosition());
				}
			}
			enumType.delete(getPosition());
			return enumType;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			try {
				applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
		}
	}

	public static class RenameEnumMigration extends EnumTypeMigration {
		public final LocatedPositionedContent renameTo;

		public RenameEnumMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			renameTo = session.attributes().get("rename-to").getLocatedContent();
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEnumType applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName.toString());
			if (enumType == null)
				throw new QonfigInterpretationException("No such enum '" + enumName + "'", enumName);
			enumType.setName(renameTo);
			return enumType;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			try {
				applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
		}
	}

	public static abstract class EnumValueMigration extends EnumTypeMigration {
		public final LocatedPositionedContent valueName;

		protected EnumValueMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			valueName = session.attributes().get("value").getLocatedContent();
		}
	}

	public static class AddValueMigration extends EnumValueMigration {
		public AddValueMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEnumValue applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName.toString());
			if (enumType == null)
				throw new QonfigInterpretationException("No such enum '" + enumName + "'", enumName);
			return enumType.addValue(valueName);
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			try {
				applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
		}
	}

	public static class RemoveValueMigration extends EnumValueMigration {
		public RemoveValueMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);

			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEnumValue applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName.toString());
			if (enumType == null)
				throw new QonfigInterpretationException("No such enum '" + enumName + "'", enumName);
			ModifiableEnumValue value = enumType.getValue(valueName.toString());
			if (value == null)
				throw new QonfigInterpretationException("No such enum value " + value, valueName);
			value.delete();
			return value;
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session)
			throws IOException, TextParseException, DataSetModificationException {
			ModifiableEnumType enumType = dataSet.getTypes().getEnumType(enumName.toString());
			if (enumType == null)
				throw new DataSetModificationException("No such enum '" + enumName + "'");
			ModifiableEnumValue value = enumType.getValue(valueName.toString());
			if (value == null)
				throw new DataSetModificationException("No such enum value " + value);
			for (EntityType entityType : enumType.getReferrers()) {
				for (GenericEntity entity : dataSet.getEntities(entityType.getName())) {
					for (EntityField<EnumValue> field : enumType.getReferences(entityType)) {
						if (entity.get(field) == value)
							throw new DataSetModificationException(
								"Enum value " + value + " is referred to by " + entity + "." + field.getName());
					}
				}
			}
			value.delete();
		}
	}

	public static class RenameValueMigration extends EnumValueMigration {
		public final LocatedPositionedContent renameTo;

		public RenameValueMigration(CoreSession session) throws QonfigInterpretationException {
			super(session);
			renameTo = session.attributes().get("rename-to").getLocatedContent();
			SchemaHistory history = session.get(SchemaHistory.HISTORY, SchemaHistory.class);
			if (history != null)
				applySchemaChange(history.getTypeSet());
		}

		@Override
		public ModifiableEnumValue applySchemaChange(ModifiableEntityTypeSet entities) throws QonfigInterpretationException {
			ModifiableEnumType enumType = entities.getEnumType(enumName.toString());
			if (enumType == null)
				throw new QonfigInterpretationException("No such enum '" + enumName + "'", enumName);
			ModifiableEnumValue value = enumType.getValue(valueName.toString());
			if (value == null)
				throw new QonfigInterpretationException("No such enum value " + value, valueName);
			return value.setName(renameTo);
		}

		@Override
		public void apply(MigratableDataSet dataSet, MigrationSession session) throws DataSetModificationException {
			EnumValue value;
			try {
				value = applySchemaChange(dataSet.getTypes());
			} catch (QonfigInterpretationException e) {
				throw new DataSetModificationException(e.getMessage(), e);
			}
			for (EntityType entityType : value.getType().getReferrers()) {
				dataSet.entityAffected(entityType);
			}
		}
	}
}
