package org.qommons.data.types.modifiable;

import org.qommons.data.migration.MigrationException;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldMapping;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.FilePosition;

public class FieldMappingPrecursor<K, S> {
	public final ModifiableEntityField<GenericEntity> mappedReferenceField;
	public final ModifiableEntityField<K> keyField;
	public final ModifiableEntityField<Integer> indexField;
	public final ModifiableEntityField<S> sortByField;
	public final boolean parentIsOwner;

	public <F> FieldMappingPrecursor(ModifiableEntityType entity, String parentFieldName, FieldType<F> type, String mapReference,
		String key, String index, String sortBy, boolean parentIsOwner, FilePosition source) throws MigrationException {
		this.parentIsOwner = parentIsOwner;

		EntityType target;
		FieldType<?> keyType = null;
		if (type instanceof EntityType) {
			if (index != null)
				throw new MigrationException(entity + "." + parentFieldName + ": index is not valid for entity-typed fields", source);
			else if (sortBy != null)
				throw new MigrationException(entity + "." + parentFieldName + ": sort-by is not valid for entity-typed fields", source);
			target = (EntityType) type;
		} else if (type instanceof FieldType.CollectionType) {
			FieldType.CollectionType<?, ?> collType = (FieldType.CollectionType<?, ?>) type;
			if (collType.isSorted) {
				if (index != null)
					throw new MigrationException(entity + "." + parentFieldName + ": index is incompatible with sorted fields", source);
			} else if (sortBy != null)
				throw new MigrationException(
					entity + "." + parentFieldName + ": Mapped collection fields must be sorted if sort-by is used", source);
			if (!collType.isSorted && !collType.isDistinct && index == null)
				throw new MigrationException(
					entity + "." + parentFieldName + ": Mapped collection fields that are not distinct or sorted require index", source);

			if (collType.componentType instanceof EntityType)
				target = (EntityType) collType.componentType;
			else
				throw new MigrationException(entity + "." + parentFieldName + ": Mapped collection fields must have entity-typed elements",
					source);
		} else if (type instanceof FieldType.MapType) {
			FieldType.MapType<?, ?, ?> mapType = (FieldType.MapType<?, ?, ?>) type;
			if (mapType.isSorted && index != null)
				throw new MigrationException(entity + "." + parentFieldName + ": index is incompatible with sorted map fields", source);
			else if (sortBy != null)
				throw new MigrationException(entity + "." + parentFieldName + ": sort-by cannot be used with map-type fields", source);
			if (mapType.valueType instanceof EntityType)
				target = (EntityType) mapType.valueType;
			else
				throw new MigrationException(entity + "." + parentFieldName + ": Mapped map fields must have entity-typed values", source);
			keyType = mapType.keyType;
		} else if (type instanceof FieldType.MultiMapType) {
			FieldType.MultiMapType<?, ?, ?> mapType = (FieldType.MultiMapType<?, ?, ?>) type;
			if (mapType.valueType instanceof EntityType)
				target = (EntityType) mapType.valueType;
			else
				throw new MigrationException(entity + "." + parentFieldName + ": Mapped multi-map fields must have entity-typed values",
					source);
			keyType = mapType.keyType;
		} else
			throw new MigrationException(entity + "." + parentFieldName
				+ ": Mapped fields must be of type entity, collection of entities, or map or multi-map with entity values. " + type
				+ " does not fit any of these criteria.", source);

		mappedReferenceField = (ModifiableEntityField<GenericEntity>) target.getField(mapReference);
		if (mappedReferenceField == null)
			throw new MigrationException("No such mapped-reference field " + target + "." + mapReference, source);
		if (key == null)
			this.keyField = null;
		else {
			this.keyField = (ModifiableEntityField<K>) target.getField(key);
			if (this.keyField == null)
				throw new MigrationException("No such key field " + target + "." + key, source);
		}
		if (index == null)
			this.indexField = null;
		else {
			this.indexField = (ModifiableEntityField<Integer>) target.getField(index);
			if (this.indexField == null)
				throw new MigrationException("No such index field " + target + "." + index, source);
		}
		if (sortBy == null)
			this.sortByField = null;
		else {
			this.sortByField = (ModifiableEntityField<S>) target.getField(sortBy);
			if (this.sortByField == null)
				throw new MigrationException("No such sort-by field " + target + "." + sortBy, source);
		}

		if (mappedReferenceField.getType() != entity)
			throw new MigrationException("The type of mapped-reference field " + mappedReferenceField.getOwner() + "."
				+ mappedReferenceField.getName() + " should be the owner of the mapped field (" + entity + ")", source);
		else if (mappedReferenceField.getOwner() != target)
			throw new MigrationException(
				"The owner of mapped field " + mappedReferenceField + " should be the entity type of the field (" + target + ")", source);

		if (keyType != null) {
			if (keyField == null)
				throw new MigrationException("Mapped map- or multi-map type fields require the key field", source);
			else if (!keyField.getOwner().isAssignableFrom(target))
				throw new MigrationException(
					"The owner of key field " + keyField + " should be the entity type of the field (" + target + ")", source);
		} else if (keyField != null)
			throw new MigrationException(entity + "." + parentFieldName + ": key is only valid for map or multi-map field types", source);

		if (indexField != null) {
			if (!indexField.getOwner().isAssignableFrom(target))
				throw new MigrationException(
					"The owner of index field " + indexField + " should be the entity type of the field (" + target + ")", source);
			else if (indexField.getType() != FieldType.SimpleType.INT)
				throw new MigrationException("index field must be typed int: " + indexField, source);
		}
	}

	public <F> FieldMapping<F, K, S> createMapping(ModifiableEntityField<F> field) {
		return new FieldMapping<>(field, mappedReferenceField, keyField, indexField, sortByField, parentIsOwner);
	}
}
