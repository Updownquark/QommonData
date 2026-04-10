package org.qommons.data.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.qommons.IterableUtils;
import org.qommons.StringUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.tree.BetterTreeSet;

public class InMemoryEntitySet implements GenericEntitySet {
	static class EntitySearch implements Comparable<GenericEntity> {
		private final Object[] theId;

		EntitySearch(Object[] id) {
			theId = id;
		}

		@Override
		public int compareTo(GenericEntity o) {
			for (int i = 0; i < theId.length; i++) {
				EntityField<Object> field = (EntityField<Object>) o.getType().getIdFields().get(i);
				int comp = field.getType().compare(theId[i], o.get(field));
				if (comp != 0)
					return comp;
			}
			return 0;
		}
	}

	private final EntityTypeSet theDataTypes;
	private final Map<EntityType, BetterSortedSet<GenericEntity>> theEntities;
	private boolean isAffected;
	private final Set<EntityType> theAffectedEntities;

	public InMemoryEntitySet(EntityTypeSet dataTypes) {
		theDataTypes = dataTypes;
		theEntities = new HashMap<>();
		for (EntityType entity : dataTypes.getEntityTypes()) {
			if (entity.getSuperType() == null)
				theEntities.put(entity, BetterTreeSet.createTreeSet(entity));
		}
		theAffectedEntities = new HashSet<>();
	}

	@Override
	public EntityTypeSet getTypes() {
		return theDataTypes;
	}

	@Override
	public Iterable<GenericEntity> getEntities(String typeName) {
		EntityType type = theDataTypes.getEntityType(typeName);
		if (type == null)
			throw new IllegalArgumentException("No such entity type '" + typeName + "'");
		BetterSortedSet<GenericEntity> entities = theEntities.get(type.getRootType());
		if (type.getSuperType() == null) // Root type
			return Collections.unmodifiableList(entities);
		else
			return IterableUtils.filter(entities, type::isInstance);
	}

	@Override
	public GenericEntity getEntity(String typeName, Object... id) throws IllegalArgumentException {
		EntityType type = theDataTypes.getEntityType(typeName);
		if (type == null)
			throw new IllegalArgumentException("No such entity type '" + typeName + "'");
		else if (type.getIdFields().size() != id.length)
			throw new IllegalArgumentException(
				"Entity type " + type + " has " + type.getIdFields().size() + " ID fields, not " + id.length);
		BetterSortedSet<GenericEntity> entities = theEntities.get(type.getRootType());
		GenericEntity found = entities.searchValue(new EntitySearch(id), SortedSearchFilter.OnlyMatch);
		if (found != null && !type.isInstance(found))
			return null;
		return found;
	}

	@Override
	public GenericEntity createEntity(String typeName) {
		EntityType type = theDataTypes.getEntityType(typeName);
		if (type== null)
			throw new IllegalArgumentException("No such entity type '" + typeName + "'");
		if (type.getIdFields().size() != 1)
			throw new IllegalArgumentException("Creating entities without an ID specified is only unambiguous"
				+ " for types with a single, incrementable ID field. " + type + " has ID " + type.getIdFields());
		EntityField<?> idField = type.getIdFields().getFirst();
		if (!MigrationUtil.isIncrementable(idField.getType()))
			throw new IllegalArgumentException("Creating entities without an ID specified is only unambiguous"
				+ " for types with a single, incrementable ID field. Type of " + idField + " is not incrementable");
		Object[] fieldValues = new Object[type.getFields().size()];
		InMemoryEntity entity = new InMemoryEntity(type, this, fieldValues);
		BetterSortedSet<GenericEntity> entities = theEntities.get(type.getRootType());
		populateIdValue(entities, entity, fieldValues, idField);
		entityAffected(type);
		return entity;
	}

	private void populateIdValue(BetterSortedSet<GenericEntity> entities, InMemoryEntity entity, Object[] fieldValues,
		EntityField<?> idField) {
		GenericEntity last=entities.peekLast();
		int idIndex = entity.getType().indexOf(idField);
		if(last==null) { //First of its kind
			fieldValues[idIndex] = MigrationUtil.getInitialValue(idField.getType());
			entities.add(entity);
		} else {
			// Note that the last entity may not be the target type, but may be a relative. So the ID index may not be the same for it.
			fieldValues[idIndex] = MigrationUtil.increment(idField.getType(), last.get(idField));
			// Handle wrap-around. Not efficient here, but we handle it.
			// Going to assume here that we don't have the full set of IDs present. That would be at least 2^32, so we should be ok.
			while (!entities.add(entity))
				fieldValues[idIndex] = MigrationUtil.increment(idField.getType(), fieldValues[idIndex]);
		}
	}

	@Override
	public GenericEntity createEntity(String typeName, Object... ids) {
		EntityType type = theDataTypes.getEntityType(typeName);
		if (type == null)
			throw new IllegalArgumentException("No such entity type '" + typeName + "'");
		if (ids.length == type.getIdFields().size()) { // All ID fields specified, easy peasy
		} else if (ids.length == type.getIdFields().size() - 1) {
			// We allow the caller to specify all ID values but one if the last ID field is incrementable.
			// We can choose a value with all the given ID fields but a new value for the last.
			if (!MigrationUtil.isIncrementable(type.getIdFields().getLast().getType()))
				throw new IllegalArgumentException("Creating entities with the last ID value unspecified is only unambiguous"
					+ " if the last ID field is incrementable. Type of " + type.getIdFields().getLast() + " is not incrementable");
		} else
			throw new IllegalArgumentException("Entity type " + type + " has " + type.getIdFields().size() + " ID fields, but " + ids.length
				+ " ID values were specified");
		Object[] fieldValues = new Object[type.getFields().size()];
		int i = 0;
		for (EntityField<?> field : type.getIdFields()) {
			if (i == ids.length)
				break;
			if (ids[i] != null && !field.getType().isInstance(ids[i]))
				throw new IllegalArgumentException(
					"Value " + ids[i] + " " + (ids[i] == null ? "" : " (type " + ids[i].getClass().getName() + ")")
					+ " is not valid for ID field " + field + " (index " + i + ")");
			int index = type.indexOf(field);
			fieldValues[index] = ids[i++];
		}
		InMemoryEntity entity = new InMemoryEntity(type, this, fieldValues);
		BetterSortedSet<GenericEntity> entities = theEntities.get(type.getRootType());
		if (ids.length < type.getIdFields().size()) { // Generate the last ID value
			Comparable<GenericEntity> range = e -> {
				for (int f = 0; f < ids.length; f++) {
					EntityField<Object> field = (EntityField<Object>) type.getIdFields().get(f);
					int comp = field.getType().compare(ids[f], e.get(field));
					if (comp != 0)
						return comp;
				}
				return 0;
			};
			populateIdValue(entities.subSet(range, range), entity, fieldValues, type.getIdFields().getLast());
		} else {
			GenericEntity found = entities.getOrAdd(entity, null, null, false, null, null).get();
			if (found == entity) { // Well and good
			} else if (found.getType() == type)
				throw new IllegalArgumentException("Another " + type + " exists with ID " + Arrays.toString(ids));
			else
				throw new IllegalArgumentException("A " + found.getType() + " exists with ID " + Arrays.toString(ids));
		}
		entityAffected(type);
		return entity;
	}

	private void deleteEntity(GenericEntity entity) {
		BetterSortedSet<GenericEntity> entities = theEntities.get(entity.getType().getRootType());
		if (entities == null)
			throw new IllegalStateException("Entity type " + entity.getType() + " has been deleted from this data source");
		else if (entities.remove(entity))
			entityAffected(entity.getType());
		else
			throw new IllegalArgumentException("This entity has already been deleted");
	}

	private void entityAffected(GenericEntity entity) {
		entityAffected(entity.getType());
	}

	protected void entityTypeCreated(EntityType entityType) throws DataSetModificationException {
		if (entityType.getSuperType() == null)
			theEntities.put(entityType, BetterTreeSet.createTreeSet(entityType));
		entityAffected(entityType);
	}

	protected void entityTypeRemoved(EntityType entityType) throws DataSetModificationException {
		if (entityType.getSuperType() == null)
			theEntities.put(entityType, BetterTreeSet.createTreeSet(entityType));
		isAffected = true;
		theAffectedEntities.remove(entityType);
	}

	protected void entityTypeRenamed(EntityType entityType, String oldName) throws DataSetModificationException {
		entityAffected(entityType);
	}

	protected <F> void entityFieldAdded(EntityField<F> field, F initValue) throws DataSetModificationException {
		BetterSortedSet<GenericEntity> entities = theEntities.get(field.getOwner().getRootType());
		if (!entities.isEmpty()) {
			Map<EntityType, Integer> fieldIndexes = new HashMap<>();
			for (GenericEntity entity : entities) {
				Integer fieldIndex = fieldIndexes.get(entity.getType());
				if (fieldIndex == null) {
					if (!field.getOwner().isInstance(entity))
						continue;
					fieldIndex = entity.getType().indexOf(field);
					fieldIndexes.put(entity.getType(), fieldIndex);
				}
				((InMemoryEntity) entity).fieldAdded(fieldIndex, initValue);
			}
		}
		entityAffected(field.getOwner());
	}

	protected void entityFieldRemoved(EntityField field) throws DataSetModificationException {
		BetterSortedSet<GenericEntity> entities = theEntities.get(field.getOwner().getRootType());
		if (!entities.isEmpty()) {
			Map<EntityType, Integer> fieldIndexes = new HashMap<>();
			for (GenericEntity entity : entities) {
				Integer fieldIndex = fieldIndexes.get(entity.getType());
				if (fieldIndex == null) {
					if (!field.getOwner().isInstance(entity))
						continue;
					fieldIndex = entity.getType().indexOf(field);
					fieldIndexes.put(entity.getType(), fieldIndex);
				}
				((InMemoryEntity) entity).fieldRemoved(fieldIndex);
			}
		}
		entityAffected(field.getOwner());
	}

	protected void entityFieldRenamed(EntityField field, String oldName) throws DataSetModificationException {
		BetterSortedSet<GenericEntity> entities = theEntities.get(field.getOwner().getRootType());
		if (!entities.isEmpty()) {
			Map<EntityType, int[]> fieldIndexes = new HashMap<>();
			for (GenericEntity entity : entities) {
				int[] fieldIndex = fieldIndexes.get(entity.getType());
				if (fieldIndex == null) {
					if (!field.getOwner().isInstance(entity))
						continue;
					int fromIndex = -entity.getType().getFields()
						.indexFor(f -> StringUtils.compareNumberTolerant(oldName, f.getName(), true, true)) - 1;
					int toIndex = entity.getType().getFields().indexOf(field);
					fieldIndex = new int[] { fromIndex, toIndex };
					fieldIndexes.put(entity.getType(), fieldIndex);
				}
				if (fieldIndex[0] != fieldIndex[1])
					((InMemoryEntity) entity).fieldMoved(fieldIndex[0], fieldIndex[1]);
			}
		}
		entityAffected(field.getOwner());
	}

	protected void entityAffected(EntityType entityType) {
		theAffectedEntities.add(entityType);
		isAffected = true;
	}

	static class InMemoryEntity extends AbstractGenericEntity {
		InMemoryEntity(EntityType type, InMemoryEntitySet entitySet, Object... id) {
			super(type, entitySet, id);
		}

		@Override
		public InMemoryEntitySet getEntitySet() {
			return (InMemoryEntitySet) super.getEntitySet();
		}

		@Override
		public GenericEntity set(EntityField<?> field, Object value) {
			super.set(field, value);
			getEntitySet().entityAffected(this);
			return this;
		}

		@Override
		public void delete() {
			getEntitySet().deleteEntity(this);
		}
	}
}
