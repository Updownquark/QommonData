package org.qommons.data.impl;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.qommons.ArrayUtils;
import org.qommons.Transaction;
import org.qommons.collect.BetterCollection;
import org.qommons.collect.BetterMap;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSortedList;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.ElementId;
import org.qommons.collect.ListElement;
import org.qommons.collect.MapEntryHandle;
import org.qommons.collect.ModControlledCollection;
import org.qommons.collect.ModControlledMap;
import org.qommons.collect.ModControlledMultiMap;
import org.qommons.collect.MultiEntryHandle;
import org.qommons.collect.MultiEntryValueHandle;
import org.qommons.collect.MultiMap;
import org.qommons.collect.MutableCollectionElement;
import org.qommons.collect.MutableCollectionElement.StdMsg;
import org.qommons.collect.SimpleDeque;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldMapping;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;

public abstract class AbstractGenericEntity implements GenericEntity {
	private final EntityType theType;
	private final GenericEntitySet theEntitySet;
	private Object[] theFieldValues;
	private boolean isDeleted;

	protected AbstractGenericEntity(EntityType type, GenericEntitySet entitySet, Object[] id) {
		theType = type;
		theEntitySet = entitySet;
		theFieldValues = new Object[theType.getFields().size()];
		int i = 0;
		// Fill in ID values
		for (EntityField<?> field : type.getIdFields())
			theFieldValues[type.indexOf(field)] = id[i++];
		// Initialize field structures
		i = 0;
		for (EntityField<?> field : type.getFields()) {
			if (field.getType() instanceof FieldType.ParameterizedType) {
				theFieldValues[i] = createEmptyStructure(field, (FieldType.ParameterizedType<?>) field.getType());
				if (field.getMapping() != null)
					theFieldValues[i] = controlMappedStructure(theFieldValues[i], (FieldMapping<Object, ?, ?>) field.getMapping());
				else
					theFieldValues[i] = controlUnmappedStructure(theFieldValues[i], (EntityField<Object>) field);
			}
			i++;
		}
	}

	@Override
	public EntityType getType() {
		return theType;
	}

	@Override
	public GenericEntitySet getEntitySet() {
		return theEntitySet;
	}

	@Override
	public <T> T get(EntityField<T> field) {
		int fieldIndex = theType.indexOf(field);
		if (fieldIndex < 0)
			throw new IllegalArgumentException("Field " + field + " does not belong to entity type " + theType);
		return (T) theFieldValues[fieldIndex];
	}

	@Override
	public String isEnabled(EntityField<?> field) {
		int fieldIndex = theType.indexOf(field);
		if (fieldIndex < 0)
			throw new IllegalArgumentException("Field " + field + " does not belong to entity type " + theType);
		else if (field.getType() instanceof FieldType.ParameterizedType)
			return "The structure value of field " + field + " cannot be set, but its content may be changed";
		else if (field.isId())
			return "ID fields cannot be set";

		String msg = null;
		if (field.getIndexReference() != null)
			msg = entity(get(field.getIndexReference().mappedReferenceField)).isReferenceIndexEnabled(field.getIndexReference(), this);
		return msg;
	}

	@Override
	public String isAcceptable(EntityField<?> field, Object value) {
		int fieldIndex = theType.indexOf(field);
		if (fieldIndex < 0)
			throw new IllegalArgumentException("Field " + field + " does not belong to entity type " + theType);
		else if (field.getType() instanceof FieldType.ParameterizedType)
			return "The structure value of field " + field + " cannot be set, but its content may be changed";
		else if (value != null && !field.getType().isInstance(value))
			return "Value " + value + " (type " + value.getClass().getName() + ") is not valid for field " + field;
		else if (field.isId())
			return "ID fields cannot be set";

		Object prev = theFieldValues[fieldIndex];
		if (prev != value) {
			String msg = null;
			if (prev != null && field.getMappingReference() != null)
				msg = entity(prev).checkRemoveReference(field.getMappingReference(), this);
			if (msg == null && value != null && field.getMappingReference() != null)
				entity(value).checkAddReference(field.getMappingReference(), this);
			if (msg == null && field.getIndexReference() != null)
				msg = entity(get(field.getIndexReference().mappedReferenceField)).checkReferenceIndexChange(field.getIndexReference(), this,
					prev, value);
			for (FieldMapping<?, ?, ?> mapping : field.getAncillaryMappingReferences()) {
				if (msg != null)
					break;
				GenericEntity mappedReference = get(mapping.mappedReferenceField);
				if (mappedReference != null) {
					if (mapping.keyField == field)
						msg = entity(mappedReference).checkReferenceKeyChange(mapping, this, prev, value);
					else if (mapping.sortByField == field)
						msg = entity(mappedReference).checkReferenceSortChange(mapping, this, prev, value);
				}
			}
			if (msg != null)
				return msg;
		}
		return null;
	}

	@Override
	public GenericEntity set(EntityField<?> field, Object value) {
		String msg = isAcceptable(field, value);
		if (msg != null)
			throw new IllegalArgumentException(msg);
		int fieldIndex = theType.indexOf(field);
		Object prev = theFieldValues[fieldIndex];
		SimpleDeque<Object> sortData = null;
		if (prev != value) {
			for (FieldMapping<?, ?, ?> mapping : field.getAncillaryMappingReferences()) {
				if (mapping.sortByField == field) {
					GenericEntity mappedReference = get(mapping.mappedReferenceField);
					if (mappedReference != null) {
						if (sortData == null)
							sortData = new SimpleDeque<>();
						sortData.add(entity(mappedReference).getReferenceSortData(mapping, this));
					}
				}
			}
		}
		theFieldValues[fieldIndex] = value;
		if (prev != value) {
			if (prev != null && field.getMappingReference() != null)
				entity(prev).referenceRemoved(field.getMappingReference(), this);
			if (value != null && field.getMappingReference() != null)
				entity(value).referenceAdded(field.getMappingReference(), this);
			if (field.getIndexReference() != null)
				entity(get(field.getIndexReference().mappedReferenceField)).referenceIndexChanged(field.getIndexReference(), this, prev,
					value);
			for (FieldMapping<?, ?, ?> mapping : field.getAncillaryMappingReferences()) {
				GenericEntity mappedReference = get(mapping.mappedReferenceField);
				if (mappedReference != null) {
					if (mapping.keyField == field)
						entity(mappedReference).referenceKeyChanged(mapping, this, prev, value);
					else if (mapping.sortByField == field)
						entity(mappedReference).referenceSortChanged(mapping, this, prev, value, sortData.poll());
				}
			}
		}
		return this;
	}

	@Override
	public String canDelete() {
		try (Transaction t = getEntitySet().lock(false, null)) {
			if (isDeleted)
				return null;
			// Check for references to this entity in the data set
			// Mapped fields are cheap because we have the reference to the entity with us
			int f = 0;
			for (EntityField<?> field : theType.getFields()) {
				if (field.getMappingReference() != null && theFieldValues[f] != null) {
					String msg = entity(theFieldValues[f]).checkRemoveReference(field.getMappingReference(), this);
					if (msg != null)
						return msg;
				}
				f++;
			}
			for (EntityType referrer : theType.getReferrers()) {
				Set<? extends EntityField<?>> references = theType.getReferences(referrer);
				if (references.stream().anyMatch(AbstractGenericEntity::isPreservingReference)) {
					try {
						for (GenericEntity entity : theEntitySet.getEntities(referrer.getName())) {
							if (!((AbstractGenericEntity) entity).isDeleted) {
								for (EntityField<?> field : references) {
									if (isPreservingReference(field) && refersToMe(entity.get(field), field))
										return "Entity " + entity + " refers to this entity via field " + field;
								}
							}
						}
					} catch (IOException e) { // Maybe regretting throwing this
					}
				}
			}
			return null;
		}
	}

	private static boolean isPreservingReference(EntityField<?> field) {
		if (field.getMapping() != null)
			return false;
		else if (field.getMappingReference() != null && field.getMappingReference().parentIsOwner)
			return false;
		else
			return true;
	}

	@Override
	public void delete() {
		try (Transaction t = getEntitySet().lock(true, null)) {
			if (isDeleted)
				return;
			isDeleted = true;
			String canDelete = canDelete();
			if (canDelete != null)
				throw new IllegalStateException(canDelete);

			int f = 0;
			for (EntityField<?> field : theType.getFields()) {
				if (theFieldValues[f] != null) {
					if (field.getMappingReference() != null) // Remove this entity from mapped reference fields
						entity(theFieldValues[f]).referenceRemoved(field.getMappingReference(), this);
					else if (field.getMapping() != null && field.getMapping().parentIsOwner) // Cascade to entities we own
						deleteAll(field.getType(), theFieldValues[f]);
				}
				f++;
			}
			deleted();
		}
	}

	protected abstract void deleted();

	private void deleteAll(FieldType<?> fieldType, Object value) {
		if (fieldType instanceof EntityType)
			((GenericEntity) value).delete();
		else if (fieldType instanceof FieldType.CollectionType) {
			for (GenericEntity entity : (Collection<GenericEntity>) value)
				entity.delete();
		} else if (fieldType instanceof FieldType.MapType) {
			for (GenericEntity entity : ((Map<?, GenericEntity>) value).values())
				entity.delete();
		} else if (fieldType instanceof FieldType.MultiMapType) {
			for (GenericEntity entity : ((MultiMap<?, GenericEntity>) value).values())
				entity.delete();
		}
	}

	@Override
	public boolean isDeleted() {
		return isDeleted;
	}

	protected void fieldStructureChanged(EntityField<?> field) {
	}

	private boolean refersToMe(Object fieldValue, EntityField<?> field) {
		FieldType<?> type = field.getType();
		if (type instanceof EntityType)
			return fieldValue == this;
		else if (type instanceof FieldType.CollectionType)
			return ((BetterCollection<?>) fieldValue).contains(this);
		else if (type instanceof FieldType.MapType)
			return ((BetterMap<Object, ?>) fieldValue).get(get(field.getMapping().keyField)) == this;
		else if (type instanceof FieldType.MultiMapType) {
			MultiEntryHandle<Object, ?> entry = ((BetterMultiMap<Object, ?>) fieldValue).getEntry(get(field.getMapping().keyField));
			return entry != null && entry.getValues().contains(this);
		} else
			throw new IllegalStateException("Unrecognized reference field type: " + type);
	}

	private static AbstractGenericEntity entity(Object fieldValue) {
		if (fieldValue instanceof AbstractGenericEntity)
			return (AbstractGenericEntity) fieldValue;
		else if (fieldValue instanceof FilteredEntitySetView.FilteredEntityView)
			return entity(((FilteredEntitySetView.FilteredEntityView) fieldValue).getSource());
		else
			throw new IllegalStateException("Unrecognized entity implementation " + fieldValue.getClass().getName());
	}

	private String checkRemoveReference(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference) {
		return null; // Currently can't think of any reason why a reference couldn't be removed
	}

	private void referenceRemoved(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference) {
		if (isDeleted)
			return;
		FieldType<?> type = field.parentField.getType();
		int index = theType.indexOf(field.parentField);
		if (type instanceof EntityType) {
			if (theFieldValues[index] == reference) {
				theFieldValues[index] = null;
			}
		} else if (type instanceof FieldType.CollectionType)
			((MappedEntityCollectionControl<?>) ((ModControlledCollection<?, ?>) theFieldValues[index]).getControl()).remove(reference);
		else if (type instanceof FieldType.MapType)
			((MappedEntityMapControl<Object, ?>) ((ModControlledMap<?, ?, ?>) theFieldValues[index]).getControl())
			.remove(reference.get(field.keyField), reference);
		else if (type instanceof FieldType.MultiMapType)
			((MappedEntityMultiMapControl<Object, ?>) ((ModControlledMultiMap<?, ?, ?>) theFieldValues[index]).getControl())
			.remove(reference.get(field.keyField), reference);
		else
			throw new IllegalStateException("Unrecognized mapped field type: " + type);
	}

	private String checkAddReference(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference) {
		FieldType<?> type = field.parentField.getType();
		if (type instanceof EntityType) {
			if (get(field.parentField) != null)
				return "Mapped field " + field.parentField.getName() + " of reference entity " + field.parentField.getOwner()
				+ " prevents multiple " + type + " instances from referring to the same reference";
		} else if (type instanceof FieldType.CollectionType) { // Nothing can prevent the add
		} else if (type instanceof FieldType.MapType) {
			BetterMap<Object, GenericEntity> map = (BetterMap<Object, GenericEntity>) get(field.parentField);
			Object key = reference.get(field.keyField);
			Object value = map.get(key);
			if (value != null && value != reference)
				return "Mapped field " + field.parentField.getName() + " of reference entity " + field.parentField.getOwner()
				+ " prevents multiple " + ((FieldType.MapType<?, ?, ?>) type).valueType
				+ " instances from referring to the same reference with key " + key;
		} else if (type instanceof FieldType.MultiMapType) { // Nothing can prevent the add
		} else
			throw new IllegalStateException("Unrecognized mapped field type: " + type);
		return null;
	}

	private void referenceAdded(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference) {
		FieldType<?> type = field.parentField.getType();
		int index = theType.indexOf(field.parentField);
		if (type instanceof EntityType) {
			if (theFieldValues[index] != reference)
				set(field.parentField, reference);
		} else if (type instanceof FieldType.CollectionType)
			((MappedEntityCollectionControl<?>) ((ModControlledCollection<?, ?>) theFieldValues[index]).getControl()).add(reference);
		else if (type instanceof FieldType.MapType)
			((MappedEntityMapControl<Object, ?>) ((ModControlledMap<?, ?, ?>) theFieldValues[index]).getControl())
			.add(reference.get(field.keyField), reference);
		else if (type instanceof FieldType.MultiMapType)
			((MappedEntityMultiMapControl<Object, ?>) ((ModControlledMultiMap<?, ?, ?>) theFieldValues[index]).getControl())
			.add(reference.get(field.keyField), reference);
		else
			throw new IllegalStateException("Unrecognized mapped field type: " + type);
	}

	private String checkReferenceKeyChange(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference, Object currentKey, Object newKey) {
		FieldType<?> type = field.parentField.getType();
		if (type instanceof FieldType.MapType) {
			BetterMap<Object, GenericEntity> map = (BetterMap<Object, GenericEntity>) get(field.parentField);
			Object value = map.get(newKey);
			if (value != null && value != reference)
				return "Mapped field " + field.parentField.getName() + " of reference entity " + field.parentField.getOwner()
				+ " prevents multiple " + ((FieldType.MapType<?, ?, ?>) type).valueType
				+ " instances from referring to the same reference with key " + newKey;
		} else if (type instanceof FieldType.MultiMapType) { // Nothing can prevent the change
		} else
			throw new IllegalStateException("Unrecognized mapped field type: " + type);
		return null;
	}

	private void referenceKeyChanged(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference, Object oldKey, Object newKey) {
		if (isDeleted)
			return;
		FieldType<?> type = field.parentField.getType();
		int index = theType.indexOf(field.parentField);
		if (type instanceof FieldType.MapType) {
			MappedEntityMapControl<Object, ?> control = (MappedEntityMapControl<Object, ?>) ((ModControlledMap<?, ?, ?>) theFieldValues[index])
				.getControl();
			control.remove(oldKey, reference);
			control.add(newKey, reference);
		} else if (type instanceof FieldType.MultiMapType) {
			MappedEntityMultiMapControl<Object, ?> control = (MappedEntityMultiMapControl<Object, ?>) ((ModControlledMultiMap<?, ?, ?>) theFieldValues[index])
				.getControl();
			control.remove(oldKey, reference);
			control.add(newKey, reference);
		} else
			throw new IllegalStateException("Unrecognized mapped field type: " + type);
	}

	private String isReferenceIndexEnabled(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference) {
		return field.indexField.getOwner() + "." + field.indexField.getName() + " is the indexing for " + field.parentField
			+ " and cannot be modified directly";
	}

	private String checkReferenceIndexChange(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference, Object currentIndex,
		Object newIndex) {
		if (!Objects.equals(currentIndex, newIndex))
			return field.indexField.getOwner() + "." + field.indexField.getName() + " is the indexing for " + field.parentField
				+ " and cannot be modified directly";
		else
			return null;
	}

	private void referenceIndexChanged(FieldMapping<?, ?, ?> field, AbstractGenericEntity reference, Object currentIndex, Object newIndex) {
		if (!Objects.equals(currentIndex, newIndex))
			throw new IllegalStateException("Shouldn't happen");
	}

	private <S> String checkReferenceSortChange(FieldMapping<?, ?, S> field, AbstractGenericEntity reference, Object currentSort,
		Object newSort) {
		if (field.parentField.getType() instanceof FieldType.CollectionType //
			&& ((FieldType.CollectionType<?, ?>) field.parentField.getType()).isDistinct//
			&& field.sortByField.getType().compare((S) currentSort, (S) newSort) != 0) {
			// This is the only situation in which we need to control the sort-by field.
			// We need to ensure that entities that should be in the collection have a place, which won't be true if there is a key clash.
			// For non-distinct collections, identical keys can exist side-by-side,
			// and sort-by for multi-maps orders the values, but does not enforce their distinctness.
			BetterSortedSet<GenericEntity> fieldSet = (BetterSortedSet<GenericEntity>) get(field.parentField);
			if (fieldSet.search(fieldSearch(field.sortByField, (S) newSort), SortedSearchFilter.OnlyMatch) != null)
				return field.sortByField.getOwner() + "." + field.sortByField.getName() + " is the sorting for " + field.parentField
					+ ". A " + field.mappedReferenceField.getOwner() + " with " + field.sortByField.getName() + " " + newSort
					+ " is already present in the set";
		}
		return null;
	}

	private static <S> Comparable<GenericEntity> fieldSearch(EntityField<S> sortField, S sortValue) {
		return e -> sortField.getType().compare(sortValue, e.get(sortField));
	}

	private <K, S> Object getReferenceSortData(FieldMapping<?, K, S> field, GenericEntity entity) {
		if (field.parentField.getType() instanceof FieldType.CollectionType) {
			BetterSortedList<GenericEntity> fieldColl = (BetterSortedList<GenericEntity>) get(field.parentField);
			return fieldColl.getElement(entity, true);
		} else if (field.parentField.getType() instanceof FieldType.MultiMapType) {
			BetterMultiMap<K, GenericEntity> fieldMap = (BetterMultiMap<K, GenericEntity>) get(field.parentField);
			K key = entity.get(field.keyField);
			return fieldMap.getEntry(key, entity, true);
		} else
			throw new IllegalStateException("Unrecognized mapped field type with sort-by: " + field.parentField.getType());
	}

	private <K, S> void referenceSortChanged(FieldMapping<?, K, S> field, AbstractGenericEntity reference, Object currentKey, Object newKey,
		Object sortData) {
		if (field.parentField.getType() instanceof FieldType.CollectionType) {
			MappedEntityCollectionControl<BetterSortedList<GenericEntity>> control;
			control = (MappedEntityCollectionControl<BetterSortedList<GenericEntity>>) ((ModControlledCollection<?, ?>) get(
				field.parentField)).getControl();
			control.getCollection().mutableElement(((CollectionElement<?>) sortData).getElementId()).remove();
			control.getCollection().add(reference);
		} else if (field.parentField.getType() instanceof FieldType.MultiMapType) {
			MappedEntityMultiMapControl<K, ?> control = (MappedEntityMultiMapControl<K, ?>) ((ModControlledMultiMap<?, ?, ?>) get(
				field.parentField)).getControl();
			MultiEntryValueHandle<K, GenericEntity> currentEl = (MultiEntryValueHandle<K, GenericEntity>) sortData;
			control.getMap().getEntryById(currentEl.getKeyId()).getValues().mutableElement(currentEl.getElementId()).remove();
			control.getMap().add((K) newKey, reference);
		} else
			throw new IllegalStateException("Unrecognized mapped field type with sort-by: " + field.parentField.getType());
	}

	protected void fieldAdded(int index, Object value) {
		theFieldValues = ArrayUtils.add(theFieldValues, value, index);
		EntityField<?> field = theType.getFields().get(index);
		if (field.getType() instanceof FieldType.ParameterizedType) {
			theFieldValues[index] = createEmptyStructure(field, (FieldType.ParameterizedType<?>) field.getType());
			if (field.getMapping() != null)
				theFieldValues[index] = controlMappedStructure(theFieldValues[index], (FieldMapping<Object, ?, ?>) field.getMapping());
			else
				theFieldValues[index] = controlUnmappedStructure(theFieldValues[index], (EntityField<Object>) field);
			if (value != null) {
				if (field.getType() instanceof FieldType.CollectionType)
					((Collection<Object>) theFieldValues[index]).addAll((Collection<?>) value);
				else if (field.getType() instanceof FieldType.MapType)
					((Map<Object, Object>) theFieldValues[index]).putAll((Map<?, ?>) value);
				else if (field.getType() instanceof FieldType.MultiMapType)
					((MultiMap<Object, Object>) theFieldValues[index]).putAll((MultiMap<?, ?>) value);
				else
					throw new IllegalStateException("Unrecognized parameterized type: " + field.getType());
			}
		}
	}

	protected void fieldRemoved(int index) {
		theFieldValues = ArrayUtils.remove(theFieldValues, index);
	}

	protected void fieldMoved(int fromIndex, int toIndex) {
		Object value = theFieldValues[fromIndex];
		if (fromIndex < toIndex)
			System.arraycopy(theFieldValues, fromIndex + 1, theFieldValues, fromIndex, toIndex - fromIndex);
		else
			System.arraycopy(theFieldValues, toIndex, theFieldValues, toIndex + 1, fromIndex - toIndex);
		theFieldValues[toIndex] = value;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(theFieldValues);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this)
			return true;
		else if (obj instanceof AbstractGenericEntity) {
			AbstractGenericEntity other = (AbstractGenericEntity) obj;
			if (!theType.equals(other.theType))
				return false;
			for (EntityField<?> field : theType.getIdFields()) {
				int fieldIndex = theType.indexOf(field);
				if (!Objects.equals(theFieldValues[fieldIndex], other.theFieldValues[fieldIndex]))
					return false;
			}
			return true;
		} else if (obj instanceof GenericEntity) {
			GenericEntity other = (GenericEntity) obj;
			if (!theType.equals(other.getType()))
				return false;
			int f = 0;
			for (EntityField<?> field : theType.getIdFields()) {
				if (!Objects.equals(theFieldValues[f++], other.get(field)))
					return false;
			}
			return true;
		} else
			return false;
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder(theType.getName()).append('(');
		MigrationUtil.printEntityId(str, this);
		return str.append(')').toString();
	}

	protected <T> T createEmptyStructure(EntityField<?> field, FieldType.ParameterizedType<T> type) {
		if (field.getMapping() == null || field.getMapping().sortByField == null)
			return type.createEmptyStructure();
		else if (type instanceof FieldType.CollectionType) {
			return (T) ((FieldType.CollectionType<GenericEntity, ?>) type).createEmptyCollection(field.getMapping().entitySort);
		} else if (type instanceof FieldType.MultiMapType)
			return (T) ((FieldType.MultiMapType<?, GenericEntity, ?>) type).createEmptyMultiMap(field.getMapping().entitySort);
		else
			throw new IllegalStateException("Unrecognized mapped field type for sort-by: " + type);
	}

	protected <F, K, S> F controlMappedStructure(F structure, FieldMapping<F, K, S> field) {
		FieldType<F> type = field.parentField.getType();
		if (type instanceof FieldType.CollectionType) {
			MappedEntityCollectionControl<?> control = new MappedEntityCollectionControl<>(
				(FieldMapping<? extends BetterCollection<GenericEntity>, Void, S>) field, this);
			BetterCollection<GenericEntity> collection = ModControlledCollection
				.controlCollection((BetterCollection<GenericEntity>) structure, control, control);
			((MappedEntityCollectionControl<BetterCollection<GenericEntity>>) control).init(collection);
			return (F) collection;
		} else if (type instanceof FieldType.MapType) {
			MappedEntityMapControl<Object, ?> control = new MappedEntityMapControl<>(
				(FieldMapping<? extends BetterMap<Object, GenericEntity>, Object, S>) field, this);
			BetterMap<Object, GenericEntity> map = ModControlledMap.controlMap((BetterMap<Object, GenericEntity>) structure, control,
				control);
			((MappedEntityMapControl<Object, BetterMap<Object, GenericEntity>>) control).init(map);
			return (F) map;
		} else if (type instanceof FieldType.MultiMapType) {
			MappedEntityMultiMapControl<Object, ?> control = new MappedEntityMultiMapControl<>(
				(FieldMapping<? extends BetterMultiMap<Object, GenericEntity>, Object, S>) field, this);
			BetterMultiMap<Object, GenericEntity> map = ModControlledMultiMap
				.controlMultiMap((BetterMultiMap<Object, GenericEntity>) structure, control, control);
			((MappedEntityMultiMapControl<Object, BetterMultiMap<Object, GenericEntity>>) control).init(map);
			return (F) map;
		} else
			throw new IllegalStateException("Unrecognized mapped field type: " + type);
	}

	protected <K, V, F> F controlUnmappedStructure(F structure, EntityField<F> field) {
		if (field.getType() instanceof FieldType.CollectionType) {
			return (F) ModControlledCollection.controlCollection((BetterCollection<V>) structure, null,
				new MemberCollectionControl<>(this, field));
		} else if (field.getType() instanceof FieldType.MapType) {
			return (F) ModControlledMap.controlMap((BetterMap<K, V>) structure, null, new MemberMapControl<>(this, field));
		} else if (field.getType() instanceof FieldType.MultiMapType) {
			return (F) ModControlledMultiMap.controlMultiMap((BetterMultiMap<K, V>) structure, null,
				new MemberMultiMapControl<>(this, field));
		} else
			throw new IllegalStateException("Unrecognized structure field type: " + field.getType());
	}

	protected static class MemberCollectionControl<E> implements ModControlledCollection.CollectionModificationListener<E> {
		private final AbstractGenericEntity theOwner;
		private final EntityField<?> theField;

		public MemberCollectionControl(AbstractGenericEntity owner, EntityField<?> field) {
			theOwner = owner;
			theField = field;
		}

		@Override
		public void elementAdded(CollectionElement<E> element) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void elementPreRemove(MutableCollectionElement<E> element) {
		}

		@Override
		public void elementRemoved(CollectionElement<E> element) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void elementReplaced(CollectionElement<E> element, E previousValue) {
			if (!Objects.equals(previousValue, element.get()))
				theOwner.fieldStructureChanged(theField);
		}

		@Override
		public Object elementPreMove(CollectionElement<E> element, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void elementMoved(CollectionElement<E> element, Object moveData) {
			theOwner.fieldStructureChanged(theField);
		}
	}

	protected static class MappedEntityCollectionControl<C extends BetterCollection<GenericEntity>>
	implements ModControlledCollection.CollectionModificationControl<GenericEntity>,
	ModControlledCollection.CollectionModificationListener<GenericEntity> {
		private final FieldMapping<C, Void, ?> theField;
		private final GenericEntity theOwner;
		private C theCollection;

		public MappedEntityCollectionControl(FieldMapping<C, Void, ?> field, GenericEntity owner) {
			theField = field;
			theOwner = owner;
		}

		public void init(C collection) {
			theCollection = collection;
		}

		C getCollection() {
			return theCollection;
		}

		@Override
		public String canAdd(GenericEntity value, ElementId after, ElementId before) {
			if (value == null)
				return StdMsg.ILLEGAL_ELEMENT;
			GenericEntity mappedRef = value.get(theField.mappedReferenceField);
			if (mappedRef != null && mappedRef != theOwner)
				return "Field " + theField.mappedReferenceField.getName() + " of " + value.getType() + " is set to a different "
				+ theField.mappedReferenceField.getType();
			if (value.get(theField.mappedReferenceField) != theOwner)
				return value.isAcceptable(theField.mappedReferenceField, theOwner);
			return null;
		}

		@Override
		public String canRemove(CollectionElement<GenericEntity> element) {
			if (theField.parentIsOwner)
				return element.get().canDelete();
			else if (element.get().get(theField.mappedReferenceField) == theOwner)
				return element.get().isAcceptable(theField.mappedReferenceField, null);
			else
				return null;
		}

		@Override
		public String isModifiable(CollectionElement<GenericEntity> element) {
			return null;
		}

		@Override
		public String isAcceptable(CollectionElement<GenericEntity> element, GenericEntity newValue) {
			if (element.get() == newValue)
				return null;
			String msg = canAdd(newValue, null, null);
			if (msg == null)
				msg = canRemove(element);
			return msg;
		}

		@Override
		public String canMove(CollectionElement<GenericEntity> element, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void elementAdded(CollectionElement<GenericEntity> element) {
			if (element.get().get(theField.mappedReferenceField) != theOwner)
				element.get().set(theField.mappedReferenceField, theOwner);
			if (theField.indexField != null)
				element.get().set(theField.indexField, ((ListElement<?>) element).getElementsBefore());
		}

		@Override
		public void elementPreRemove(MutableCollectionElement<GenericEntity> element) {
		}

		@Override
		public void elementRemoved(CollectionElement<GenericEntity> element) {
			entityRemoved(element.get());
		}

		private void entityRemoved(GenericEntity entity) {
			if (theField.parentIsOwner)
				entity.delete();
			else if (entity.get(theField.mappedReferenceField) == theOwner)
				entity.set(theField.mappedReferenceField, null);
		}

		@Override
		public void elementReplaced(CollectionElement<GenericEntity> element, GenericEntity previousValue) {
			entityRemoved(previousValue);
			elementAdded(element);
			if (theField.indexField != null)
				element.get().set(theField.indexField, ((ListElement<?>) element).getElementsBefore());
		}

		@Override
		public Object elementPreMove(CollectionElement<GenericEntity> element, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void elementMoved(CollectionElement<GenericEntity> element, Object moveData) {
			if (theField.indexField != null)
				element.get().set(theField.indexField, ((ListElement<?>) element).getElementsBefore());
		}

		void remove(GenericEntity entity) {
			while (theCollection.remove(entity)) { // The condition does it all
			}
		}

		void add(GenericEntity entity) {
			if (!theCollection.contains(entity))
				theCollection.add(entity);
		}
	}

	protected static class MemberMapControl<K, V> implements ModControlledMap.MapModificationListener<K, V> {
		private final AbstractGenericEntity theOwner;
		private final EntityField<?> theField;

		public MemberMapControl(AbstractGenericEntity owner, EntityField<?> field) {
			theOwner = owner;
			theField = field;
		}

		@Override
		public void entryAdded(MapEntryHandle<K, V> entry) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void entryPreRemoved(MapEntryHandle<K, V> entry) {
		}

		@Override
		public void entryRemoved(MapEntryHandle<K, V> entry) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void entryKeyChanged(MapEntryHandle<K, V> entry, K previousKey) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void entryValueChanged(MapEntryHandle<K, V> entry, V previousValue) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public Object entryPreMoved(MapEntryHandle<K, V> entry, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void entryMoved(MapEntryHandle<K, V> entry, Object moveData) {
			theOwner.fieldStructureChanged(theField);
		}
	}

	protected static class MappedEntityMapControl<K, M extends BetterMap<K, GenericEntity>>
	implements ModControlledMap.MapModificationControl<K, GenericEntity>, ModControlledMap.MapModificationListener<K, GenericEntity> {
		private final FieldMapping<M, K, ?> theField;
		private final GenericEntity theOwner;
		private M theMap;

		public MappedEntityMapControl(FieldMapping<M, K, ?> field, GenericEntity owner) {
			theField = field;
			theOwner = owner;
		}

		public void init(M map) {
			theMap = map;
		}

		M getMap() {
			return theMap;
		}

		@Override
		public String canAdd(K key, GenericEntity value, ElementId after, ElementId before) {
			if (value == null)
				return StdMsg.ILLEGAL_ELEMENT;
			GenericEntity mappedRef = value.get(theField.mappedReferenceField);
			if (mappedRef != null && mappedRef != theOwner)
				return "Field " + theField.mappedReferenceField.getName() + " of " + value.getType() + " is set to a different "
				+ theField.mappedReferenceField.getType();
			String msg = value.isAcceptable(theField.mappedReferenceField, theOwner);
			if (msg == null)
				msg = value.isAcceptable(theField.keyField, key);
			return msg;
		}

		@Override
		public String canRemove(MapEntryHandle<K, GenericEntity> entry) {
			if (theField.parentIsOwner)
				return entry.get().canDelete();
			else if (entry.get().get(theField.mappedReferenceField) == theOwner)
				return entry.get().isAcceptable(theField.mappedReferenceField, null);
			else
				return null;
		}

		@Override
		public String isKeyModifiable(MapEntryHandle<K, GenericEntity> entry) {
			return null;
		}

		@Override
		public String isKeyAcceptable(MapEntryHandle<K, GenericEntity> entry, K newKey) {
			return entry.get().isAcceptable(theField.keyField, newKey);
		}

		@Override
		public String isModifiable(MapEntryHandle<K, GenericEntity> entry) {
			return null;
		}

		@Override
		public String isAcceptable(MapEntryHandle<K, GenericEntity> entry, GenericEntity newValue) {
			if (entry.get() == newValue)
				return null;
			String msg = canAdd(entry.getKey(), newValue, null, null);
			if (msg == null)
				msg = canRemove(entry);
			return msg;
		}

		@Override
		public String canMove(MapEntryHandle<K, GenericEntity> entry, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void entryAdded(MapEntryHandle<K, GenericEntity> entry) {
			entry.get().set(theField.mappedReferenceField, theOwner);
			entry.get().set(theField.keyField, entry.getKey());
		}

		@Override
		public void entryPreRemoved(MapEntryHandle<K, GenericEntity> entry) {
		}

		@Override
		public void entryRemoved(MapEntryHandle<K, GenericEntity> entry) {
			entityRemoved(entry.get());
		}

		private void entityRemoved(GenericEntity entity) {
			if (theField.parentIsOwner)
				entity.delete();
			else if (entity.get(theField.mappedReferenceField) == theOwner)
				entity.set(theField.mappedReferenceField, null);
		}

		@Override
		public void entryKeyChanged(MapEntryHandle<K, GenericEntity> entry, K previousKey) {
			entry.get().set(theField.keyField, entry.getKey());
		}

		@Override
		public void entryValueChanged(MapEntryHandle<K, GenericEntity> entry, GenericEntity previousValue) {
			entityRemoved(previousValue);
			entryAdded(entry);
		}

		@Override
		public Object entryPreMoved(MapEntryHandle<K, GenericEntity> entry, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void entryMoved(MapEntryHandle<K, GenericEntity> entry, Object moveData) {
		}

		void remove(K key, GenericEntity entity) {
			theMap.compute(key, (k, current) -> current == entity ? null : current);
		}

		void add(K key, GenericEntity entity) {
			theMap.put(key, entity);
		}
	}

	protected static class MemberMultiMapControl<K, V> implements ModControlledMultiMap.MultiMapModificationListener<K, V> {
		private final AbstractGenericEntity theOwner;
		private final EntityField<?> theField;

		public MemberMultiMapControl(AbstractGenericEntity owner, EntityField<?> field) {
			theOwner = owner;
			theField = field;
		}

		@Override
		public void entryAdded(MultiEntryHandle<K, V> entry) {
		}

		@Override
		public void valueAdded(MultiEntryValueHandle<K, V> entry) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void entryPreRemove(MultiEntryValueHandle<K, V> entry) {
		}

		@Override
		public void entryRemoved(MultiEntryValueHandle<K, V> entry) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void entryKeyChanged(MultiEntryHandle<K, V> entry, K previousKey) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public void entryValueChanged(MultiEntryValueHandle<K, V> entry, V previousValue) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public Object entryPreMoved(MultiEntryHandle<K, V> entry, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void entryMoved(MultiEntryHandle<K, V> entry, Object moveData) {
			theOwner.fieldStructureChanged(theField);
		}

		@Override
		public Object valuePreMove(MultiEntryHandle<K, V> keyEntry, MultiEntryValueHandle<K, V> valueEntry, ElementId after,
			ElementId before) {
			return null;
		}

		@Override
		public void valueMoved(MultiEntryHandle<K, V> keyEntry, MultiEntryValueHandle<K, V> valueEntry, Object moveData) {
			theOwner.fieldStructureChanged(theField);
		}
	}

	protected static class MappedEntityMultiMapControl<K, M extends BetterMultiMap<K, GenericEntity>>
	implements ModControlledMultiMap.MultiMapModificationControl<K, GenericEntity>,
	ModControlledMultiMap.MultiMapModificationListener<K, GenericEntity> {
		private final FieldMapping<M, K, ?> theField;
		private final GenericEntity theOwner;
		private M theMap;

		public MappedEntityMultiMapControl(FieldMapping<M, K, ?> field, GenericEntity owner) {
			theField = field;
			theOwner = owner;
		}

		public void init(M map) {
			theMap = map;
		}

		M getMap() {
			return theMap;
		}

		@Override
		public String canAddEntry(K key, Iterable<? extends GenericEntity> values, ElementId after, ElementId before) {
			for (GenericEntity value : values) {
				String msg = canAdd(key, value);
				if (msg != null)
					return msg;
			}
			return null;
		}

		private String canAdd(K key, GenericEntity value) {
			if (value == null)
				return StdMsg.ILLEGAL_ELEMENT;
			GenericEntity mappedRef = value.get(theField.mappedReferenceField);
			if (mappedRef != null && mappedRef != theOwner)
				return "Field " + theField.mappedReferenceField.getName() + " of " + value.getType() + " is set to a different "
				+ theField.mappedReferenceField.getType();
			String msg = value.isAcceptable(theField.mappedReferenceField, theOwner);
			if (msg == null)
				msg = value.isAcceptable(theField.keyField, key);
			return msg;
		}

		@Override
		public String canAddValue(MultiEntryHandle<K, GenericEntity> entry, GenericEntity value, ElementId after, ElementId before) {
			return canAdd(entry.getKey(), value);
		}

		@Override
		public String canRemove(MultiEntryValueHandle<K, GenericEntity> entry) {
			if (theField.parentIsOwner)
				return entry.get().canDelete();
			else if (entry.get().get(theField.mappedReferenceField) == theOwner)
				return entry.get().isAcceptable(theField.mappedReferenceField, null);
			else
				return null;
		}

		@Override
		public String isKeyModifiable(MultiEntryHandle<K, GenericEntity> entry) {
			return null;
		}

		@Override
		public String isKeyAcceptable(MultiEntryHandle<K, GenericEntity> entry, K newKey) {
			for (GenericEntity entity : entry.getValues()) {
				String msg = entity.isAcceptable(theField.keyField, newKey);
				if (msg != null)
					return msg;
			}
			return null;
		}

		@Override
		public String isModifiable(MultiEntryValueHandle<K, GenericEntity> entry) {
			return null;
		}

		@Override
		public String isAcceptable(MultiEntryValueHandle<K, GenericEntity> entry, GenericEntity newValue) {
			if (entry.get() == newValue)
				return null;
			String msg = canAdd(entry.getKey(), newValue);
			if (msg == null)
				msg = canRemove(entry);
			return msg;
		}

		@Override
		public String canMoveEntry(MultiEntryHandle<K, GenericEntity> entry, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public String canMoveValue(MultiEntryHandle<K, GenericEntity> keyEntry, MultiEntryValueHandle<K, GenericEntity> valueEntry,
			ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void entryAdded(MultiEntryHandle<K, GenericEntity> entry) {
		}

		@Override
		public void valueAdded(MultiEntryValueHandle<K, GenericEntity> entry) {
			entry.get().set(theField.mappedReferenceField, theOwner);
			entry.get().set(theField.keyField, entry.getKey());
		}

		@Override
		public void entryPreRemove(MultiEntryValueHandle<K, GenericEntity> entry) {
		}

		@Override
		public void entryRemoved(MultiEntryValueHandle<K, GenericEntity> entry) {
			entityRemoved(entry.get());
		}

		private void entityRemoved(GenericEntity entity) {
			if (theField.parentIsOwner)
				entity.delete();
			else if (entity.get(theField.mappedReferenceField) == theOwner)
				entity.set(theField.mappedReferenceField, null);
		}

		@Override
		public void entryKeyChanged(MultiEntryHandle<K, GenericEntity> entry, K previousKey) {
			for (GenericEntity entity : entry.getValues())
				entity.set(theField.keyField, entry.getKey());
		}

		@Override
		public void entryValueChanged(MultiEntryValueHandle<K, GenericEntity> entry, GenericEntity previousValue) {
			entityRemoved(previousValue);
			valueAdded(entry);
		}

		@Override
		public Object entryPreMoved(MultiEntryHandle<K, GenericEntity> entry, ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void entryMoved(MultiEntryHandle<K, GenericEntity> entry, Object moveData) {
		}

		@Override
		public Object valuePreMove(MultiEntryHandle<K, GenericEntity> keyEntry, MultiEntryValueHandle<K, GenericEntity> valueEntry,
			ElementId after, ElementId before) {
			return null;
		}

		@Override
		public void valueMoved(MultiEntryHandle<K, GenericEntity> keyEntry, MultiEntryValueHandle<K, GenericEntity> valueEntry,
			Object moveData) {
		}

		void remove(K key, GenericEntity entity) {
			MultiEntryHandle<K, GenericEntity> entry = theMap.getEntry(key);
			if (entry != null) {
				while (entry.getValues().remove(entity)) { // The condition does it all
				}
			}
		}

		void add(K key, GenericEntity entity) {
			MultiEntryHandle<K, GenericEntity> entry = theMap.getEntry(key);
			if (entry == null)
				theMap.add(key, entity);
			else if (!entry.getValues().contains(entity))
				entry.getValues().add(entity);
		}
	}
}
