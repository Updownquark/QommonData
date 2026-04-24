package org.qommons.data.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.qommons.IterableUtils;
import org.qommons.StringUtils;
import org.qommons.collect.BetterCollection;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.CollectionLockingStrategy;
import org.qommons.collect.FastFailLockingStrategy;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.FieldType;
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
	private final CollectionLockingStrategy theLock;
	private final Set<EntityType> theAffectedEntities;

	public InMemoryEntitySet(EntityTypeSet dataTypes, Function<? super InMemoryEntitySet, ? extends CollectionLockingStrategy> locking) {
		theDataTypes = dataTypes;
		theEntities = new HashMap<>();
		theLock = locking == null ? new FastFailLockingStrategy() : locking.apply(this);
		for (EntityType entity : dataTypes.getEntityTypes()) {
			if (entity.getSuperTypes().isEmpty())
				theEntities.put(entity, createEntitySet(entity, theLock));
		}
		theAffectedEntities = new HashSet<>();
	}

	protected BetterSortedSet<GenericEntity> createEntitySet(EntityType type, CollectionLockingStrategy locking) {
		return BetterTreeSet.createTreeSet(type, b -> b.withLocking(locking));
	}

	@Override
	public EntityTypeSet getTypes() {
		return theDataTypes;
	}

	protected CollectionLockingStrategy getLock() {
		return theLock;
	}

	public Set<EntityType> getAffectedEntities() {
		// Not an unmodifiable view. Allow the caller to remove entities.
		// We'd prefer they don't add entities, but it's a little hard to allow the one without the other.
		return theAffectedEntities;
	}

	protected BetterSortedSet<GenericEntity> getInternalEntities(EntityType entity) {
		return theEntities.get(entity);
	}

	@Override
	public Iterable<GenericEntity> getEntities(String typeName) {
		EntityType type = theDataTypes.getEntityType(typeName);
		if (type == null)
			throw new IllegalArgumentException("No such entity type '" + typeName + "'");
		BetterSortedSet<GenericEntity> entities = getInternalEntities(type.getRootType());
		if (type.getSuperTypes().isEmpty()) // Root type
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
		BetterSortedSet<GenericEntity> entities = getInternalEntities(type.getRootType());
		GenericEntity found = entities.searchValue(new EntitySearch(id), SortedSearchFilter.OnlyMatch);
		if (found != null && !type.isInstance(found))
			return null;
		return found;
	}

	@Override
	public boolean isMember(GenericEntity entity) {
		BetterSortedSet<GenericEntity> entities = getInternalEntities(entity.getType().getRootType());
		if (entities == null)
			return false;
		CollectionElement<GenericEntity> element = entities.getElement(entity, true);
		return element != null && element.get() == entity;
	}

	@Override
	public GenericEntity createEntity(String typeName) {
		EntityType type = theDataTypes.getEntityType(typeName);
		if (type == null)
			throw new IllegalArgumentException("No such entity type '" + typeName + "'");
		if (type.getIdFields().size() != 1)
			throw new IllegalArgumentException("Creating entities without an ID specified is only unambiguous"
				+ " for types with a single, incrementable ID field. " + type + " has ID " + type.getIdFields());
		EntityField<?> idField = type.getIdFields().getFirst();
		if (!MigrationUtil.isIncrementable(idField.getType()))
			throw new IllegalArgumentException("Creating entities without an ID specified is only unambiguous"
				+ " for types with a single, incrementable ID field. Type of " + idField + " is not incrementable");
		BetterSortedSet<GenericEntity> entities = getInternalEntities(type.getRootType());
		Object[] id = new Object[type.getIdFields().size()];
		populateIdValue(entities, type, id, idField);
		return createEntity(type.getName(), id);
	}

	private void populateIdValue(BetterSortedSet<GenericEntity> entities, EntityType type, Object[] idValues, EntityField<?> idField) {
		GenericEntity last = entities.peekLast();
		int idIndex = type.getIdFields().indexOf(idField);
		if (last == null) { // First of its kind
			idValues[idIndex] = MigrationUtil.getInitialValue(idField.getType());
		} else {
			// Note that the last entity may not be the target type, but may be a relative. So the ID index may not be the same for it.
			idValues[idIndex] = MigrationUtil.adjust(idField.getType(), last.get(idField), true);
			if (idValues[idIndex] == null)
				idValues[idIndex] = MigrationUtil.adjust(idField.getType(), last.get(idField), false);
			if (idValues[idIndex] == null)
				throw new IllegalStateException("ID set is full");
			// Handle wrap-around. Not efficient here, but we handle it.
			// Going to assume here that we don't have the full set of IDs present. That would be at least 2^32, so we should be ok.
			boolean increment = true;
			while (entities.search(entity -> -entity.compareToId(idValues), SortedSearchFilter.OnlyMatch) != null) {
				idValues[idIndex] = MigrationUtil.adjust(idField.getType(), idValues[idIndex], increment);
				if (idValues[idIndex] == null) {
					if (increment)
						increment = false;
					else
						throw new IllegalStateException("ID set is full");
				}
			}
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
		int i = 0;
		for (EntityField<?> field : type.getIdFields()) {
			if (ids[i] != null && !field.getType().isInstance(ids[i]))
				throw new IllegalArgumentException(
					"Value " + ids[i] + " " + (ids[i] == null ? "" : " (type " + ids[i].getClass().getName() + ")")
					+ " is not valid for ID field " + field + " (index " + i + ")");
			i++;
		}
		BetterSortedSet<GenericEntity> entities = getInternalEntities(type.getRootType());
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
			populateIdValue(entities.subSet(range, range), type, ids, type.getIdFields().getLast());
		} else {
			GenericEntity found = entities.searchValue(e -> -e.compareToId(ids), SortedSearchFilter.OnlyMatch);
			if (found == null) { // Well and good
			} else if (found.getType() == type)
				throw new IllegalArgumentException("Another " + type + " exists with ID " + Arrays.toString(ids));
			else
				throw new IllegalArgumentException("A " + found.getType() + " exists with ID " + Arrays.toString(ids));
		}
		AbstractGenericEntity entity = createEntity(type, ids);
		addEntity(entity);
		return entity;
	}

	protected CollectionElement<GenericEntity> addEntity(AbstractGenericEntity entity) {
		EntityType type = entity.getType();
		BetterSortedSet<GenericEntity> entities = getInternalEntities(type.getRootType());
		CollectionElement<GenericEntity> added = entities.addElement(entity, false);
		for (EntityField<?> field : type.getIdFields()) {
			if (field.getMappingReference() != null) {
				GenericEntity value = (GenericEntity) entity.get(field);
				if (value != null) {
					FieldType<?> parentFieldType = field.getMappingReference().parentField.getType();
					if (parentFieldType instanceof EntityType) {
						if (value.get(field.getMappingReference().parentField) != null)
							throw new IllegalArgumentException(
								"Entity " + value + "'s " + field.getMappingReference().parentField.getName() + " is already populated");
						value.set(field.getMappingReference().parentField, entity);
					} else if (parentFieldType instanceof FieldType.CollectionType) {
						BetterCollection<GenericEntity> collection = (BetterCollection<GenericEntity>) value
							.get(field.getMappingReference().parentField);
						if (field.getMappingReference().sortByField != null
							&& ((FieldType.CollectionType<?, ?>) parentFieldType).isDistinct) {
							EntityField<Object> sortBy = (EntityField<Object>) field.getMappingReference().sortByField;
							Object sortValue = entity.get(sortBy);
							if (((BetterSortedSet<GenericEntity>) collection)
								.search(e -> sortBy.getType().compare(sortValue, e.get(sortBy)), SortedSearchFilter.OnlyMatch) != null) {
								throw new IllegalArgumentException(
									"Entity " + value + "'s " + field.getMappingReference().parentField.getName()
									+ " already has a value with " + sortBy.getName() + " " + sortValue);
							}
						} // else No issue, just add the new entity
						collection.add(entity);
					} else if (parentFieldType instanceof FieldType.MapType) {
						Map<Object, GenericEntity> map = (Map<Object, GenericEntity>) value.get(field.getMappingReference().parentField);
						Object key = entity.get(field.getMappingReference().keyField);
						GenericEntity current = map.get(key);
						if (current != null && current != entity)
							throw new IllegalArgumentException("Entity " + value + "'s " + field.getMappingReference().parentField.getName()
								+ " already has a value for key " + key);
						map.put(key, entity);
					} else if (parentFieldType instanceof FieldType.MultiMapType) {
						BetterMultiMap<Object, GenericEntity> map = (BetterMultiMap<Object, GenericEntity>) value
							.get(field.getMappingReference().parentField);
						// No issue, just add the new entity
						map.add(entity.get(field.getMappingReference().keyField), entity);
					} else
						throw new IllegalStateException("Unhandled mapping field type: " + field.getMappingReference().parentField);
				}
			}
		}
		entityAffected(type);
		return added;
	}

	protected void deleteEntity(GenericEntity entity) {
		BetterSortedSet<GenericEntity> entities = getInternalEntities(entity.getType().getRootType());
		if (entities == null)
			throw new IllegalStateException("Entity type " + entity.getType() + " has been deleted from this data source");
		else if (entities.remove(entity))
			entityAffected(entity.getType());
		else
			throw new IllegalArgumentException("This entity has already been deleted");
	}

	protected void entityAffected(GenericEntity entity) {
		entityAffected(entity.getType());
	}

	protected void entityTypeCreated(EntityType entityType) throws DataSetModificationException {
		if (entityType.getSuperTypes().isEmpty())
			theEntities.put(entityType, BetterTreeSet.createTreeSet(entityType));
		entityAffected(entityType);
	}

	protected void entityTypeRemoved(EntityType entityType) throws DataSetModificationException {
		if (entityType.getSuperTypes().isEmpty())
			theEntities.put(entityType, BetterTreeSet.createTreeSet(entityType));
		theAffectedEntities.remove(entityType);
	}

	protected void entityTypeRenamed(EntityType entityType, String oldName) throws DataSetModificationException {
		entityAffected(entityType);
	}

	protected <F> void entityFieldAdded(EntityField<F> field, F initValue) throws DataSetModificationException {
		BetterSortedSet<GenericEntity> entities = getInternalEntities(field.getOwner().getRootType());
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
		BetterSortedSet<GenericEntity> entities = getInternalEntities(field.getOwner().getRootType());
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
		BetterSortedSet<GenericEntity> entities = getInternalEntities(field.getOwner().getRootType());
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
	}

	protected AbstractGenericEntity createEntity(EntityType type, Object[] id) {
		return new InMemoryEntity(type, this, id);
	}

	protected static class InMemoryEntity extends AbstractGenericEntity {
		protected InMemoryEntity(EntityType type, InMemoryEntitySet entitySet, Object[] id) {
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
		protected void fieldStructureChanged(EntityField<?> field) {
			super.fieldStructureChanged(field);
			getEntitySet().entityAffected(this);
		}

		@Override
		protected void deleted() {
			getEntitySet().deleteEntity(this);
		}
	}
}
