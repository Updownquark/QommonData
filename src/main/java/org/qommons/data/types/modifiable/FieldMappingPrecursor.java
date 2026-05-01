package org.qommons.data.types.modifiable;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldMapping;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;

public class FieldMappingPrecursor<K, S> {
	public final ModifiableEntityField<GenericEntity> mappedReferenceField;
	public final ModifiableEntityField<K> keyField;
	public final ModifiableEntityField<Integer> indexField;
	public final ModifiableEntityField<S> sortByField;
	public final boolean parentIsOwner;

	public <F> FieldMappingPrecursor(ModifiableEntityType entity, LocatedPositionedContent parentFieldName, FieldType<F> type,
		LocatedPositionedContent mapReference, LocatedPositionedContent key, LocatedPositionedContent index,
		LocatedPositionedContent sortBy, boolean parentIsOwner) throws QonfigInterpretationException {
		this.parentIsOwner = parentIsOwner;

		EntityType target;
		FieldType<?> keyType = null;
		if (type instanceof EntityType) {
			if (index != null)
				throw new QonfigInterpretationException(entity + "." + parentFieldName + ": index is not valid for entity-typed fields",
					index);
			else if (sortBy != null)
				throw new QonfigInterpretationException(entity + "." + parentFieldName + ": sort-by is not valid for entity-typed fields",
					sortBy);
			target = (EntityType) type;
		} else if (type instanceof FieldType.CollectionType) {
			FieldType.CollectionType<?, ?> collType = (FieldType.CollectionType<?, ?>) type;
			if (collType.isSorted) {
				if (index != null)
					throw new QonfigInterpretationException(entity + "." + parentFieldName + ": index is incompatible with sorted fields",
						index);
			} else if (sortBy != null)
				throw new QonfigInterpretationException(
					entity + "." + parentFieldName + ": Mapped collection fields must be sorted if sort-by is used", sortBy);
			if (!collType.isSorted && !collType.isDistinct && index == null)
				throw new QonfigInterpretationException(
					entity + "." + parentFieldName + ": Mapped collection fields that are not distinct or sorted require index",
					parentFieldName);

			if (collType.componentType instanceof EntityType)
				target = (EntityType) collType.componentType;
			else
				throw new QonfigInterpretationException(
					entity + "." + parentFieldName + ": Mapped collection fields must have entity-typed elements", parentFieldName);
		} else if (type instanceof FieldType.MapType) {
			FieldType.MapType<?, ?, ?> mapType = (FieldType.MapType<?, ?, ?>) type;
			if (mapType.isSorted && index != null)
				throw new QonfigInterpretationException(entity + "." + parentFieldName + ": index is incompatible with sorted map fields",
					index);
			else if (sortBy != null)
				throw new QonfigInterpretationException(entity + "." + parentFieldName + ": sort-by cannot be used with map-type fields",
					sortBy);
			if (mapType.valueType instanceof EntityType)
				target = (EntityType) mapType.valueType;
			else
				throw new QonfigInterpretationException(
					entity + "." + parentFieldName + ": Mapped map fields must have entity-typed values", parentFieldName);
			keyType = mapType.keyType;
		} else if (type instanceof FieldType.MultiMapType) {
			FieldType.MultiMapType<?, ?, ?> mapType = (FieldType.MultiMapType<?, ?, ?>) type;
			if (mapType.valueType instanceof EntityType)
				target = (EntityType) mapType.valueType;
			else
				throw new QonfigInterpretationException(
					entity + "." + parentFieldName + ": Mapped multi-map fields must have entity-typed values", parentFieldName);
			keyType = mapType.keyType;
		} else
			throw new QonfigInterpretationException(entity + "." + parentFieldName
				+ ": Mapped fields must be of type entity, collection of entities, or map or multi-map with entity values. " + type
				+ " does not fit any of these criteria.", parentFieldName);

		mappedReferenceField = (ModifiableEntityField<GenericEntity>) target.getField(mapReference.toString());
		if (mappedReferenceField == null)
			throw new QonfigInterpretationException("No such mapped-reference field " + target + "." + mapReference, mapReference);
		if (key == null)
			this.keyField = null;
		else {
			this.keyField = (ModifiableEntityField<K>) target.getField(key.toString());
			if (this.keyField == null)
				throw new QonfigInterpretationException("No such key field " + target + "." + key, key);
		}
		if (index == null)
			this.indexField = null;
		else {
			this.indexField = (ModifiableEntityField<Integer>) target.getField(index.toString());
			if (this.indexField == null)
				throw new QonfigInterpretationException("No such index field " + target + "." + index, index);
		}
		if (sortBy == null)
			this.sortByField = null;
		else {
			this.sortByField = (ModifiableEntityField<S>) target.getField(sortBy.toString());
			if (this.sortByField == null)
				throw new QonfigInterpretationException("No such sort-by field " + target + "." + sortBy, sortBy);
		}

		if (mappedReferenceField.getType() != entity)
			throw new QonfigInterpretationException("The type of mapped-reference field " + mappedReferenceField.getOwner() + "."
				+ mappedReferenceField.getName() + " should be the owner of the mapped field (" + entity + ")", mapReference);
		else if (mappedReferenceField.getOwner() != target)
			throw new QonfigInterpretationException(
				"The owner of mapped field " + mappedReferenceField + " should be the entity type of the field (" + target + ")",
				mapReference);

		if (keyType != null) {
			if (keyField == null)
				throw new QonfigInterpretationException("Mapped map- or multi-map type fields require the key field", mapReference);
			else if (!keyField.getOwner().isAssignableFrom(target))
				throw new QonfigInterpretationException(
					"The owner of key field " + keyField + " should be the entity type of the field (" + target + ")", key);
		} else if (keyField != null)
			throw new QonfigInterpretationException(entity + "." + parentFieldName + ": key is only valid for map or multi-map field types",
				key);

		if (indexField != null) {
			if (!indexField.getOwner().isAssignableFrom(target))
				throw new QonfigInterpretationException(
					"The owner of index field " + indexField + " should be the entity type of the field (" + target + ")", index);
			else if (indexField.getType() != FieldType.SimpleType.INT)
				throw new QonfigInterpretationException("index field must be typed int: " + indexField, index);
		}
	}

	public <F> FieldMapping<F, K, S> createMapping(ModifiableEntityField<F> field) {
		return new FieldMapping<>(field, mappedReferenceField, keyField, indexField, sortByField, parentIsOwner);
	}
}
