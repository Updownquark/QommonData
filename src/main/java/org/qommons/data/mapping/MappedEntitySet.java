package org.qommons.data.mapping;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.qommons.ClassMap;
import org.qommons.ClassMap.TypeMatch;
import org.qommons.IterableUtils;
import org.qommons.collect.BetterSortedList.SortedSearchFilter;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.MultiMap;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.io.TextParseException;
import org.qommons.tree.BetterTreeSet;

public class MappedEntitySet {
	public interface EntityMapping {
		<E> E createEntity(GenericEntity genericEntity, EntityTypeMapping<E> type, MappedEntitySet entitySet);

		<E> void populateEntity(E realEntity, GenericEntity genericEntity, EntityTypeMapping<E> type, MappedEntitySet entitySet);
	}

	public interface EntityDifferenceAccepter {
		<E> void differenceEncountered(EntityTypeMapping<E> type, E left, E right);
	}

	private final EntityTypeSetMapping theTypes;
	private final ClassMap<BetterSortedSet<?>> theEntities;

	private MappedEntitySet(EntityTypeSetMapping types, ClassMap<BetterSortedSet<?>> entities) {
		theTypes = types;
		theEntities = entities;
	}

	public static MappedEntitySet create(GenericEntitySet data, EntityTypeSetMapping mappedTypes, EntityMapping mapping)
		throws IOException, TextParseException {
		ClassMap<BetterSortedSet<?>> entities = new ClassMap<>();
		MappedEntitySet mappedEntities = new MappedEntitySet(mappedTypes, entities);
		for (EntityTypeMapping<?> type : mappedTypes.getEntityTypes().values()) {
			if (!type.getGenericType().getSuperTypes().isEmpty())
				continue;
			for (GenericEntity entity : data.getEntities(type.getName())) {
				EntityTypeMapping<?> entityType;
				if (entity.getType() == type.getGenericType())
					entityType = type;
				else
					entityType = mappedTypes.getEntityTypes().get(entity.getType().getName());
				createEntity(entityType, entity, entities, mappedEntities, mapping);
			}
		}
		for (EntityTypeMapping<?> type : mappedTypes.getEntityTypes().values()) {
			if (!type.getGenericType().getSuperTypes().isEmpty())
				continue;
			for (GenericEntity entity : data.getEntities(type.getName())) {
				EntityTypeMapping<?> entityType;
				if (entity.getType() == type.getGenericType())
					entityType = type;
				else
					entityType = mappedTypes.getEntityTypes().get(entity.getType().getName());
				Object realEntity = entities.get(entityType.getRealType(), TypeMatch.EXACT).getEquivalentValue(entity, true);
				mapping.populateEntity(realEntity, entity, (EntityTypeMapping<Object>) realEntity, mappedEntities);
			}
		}
		return mappedEntities;
	}

	private static <E> void createEntity(EntityTypeMapping<E> entityType, GenericEntity entity, ClassMap<BetterSortedSet<?>> entities,
		MappedEntitySet mappedEntities, EntityMapping mapping) {
		BetterSortedSet<E> typeEntities = (BetterSortedSet<E>) entities.computeIfAbsent(entityType.getRealType(),
			() -> BetterTreeSet.createTreeSet((Comparator<Object>) entityType.getSorting()));
		typeEntities.add(mapping.createEntity(entity, entityType, mappedEntities));
	}

	public static MappedEntitySet create(Iterable<?> entities, EntityTypeSetMapping mappedTypes) {
		MappedEntitySet entitySet = new MappedEntitySet(mappedTypes, new ClassMap<>());
		try {
			for (Object entity : entities)
				entitySet.addEntity(entity, null);

			Map<Object, Boolean> filledOut = new IdentityHashMap<>();
			for (Object entity : entities)
				entitySet.fillOut(entity, null, filledOut);
		} catch (Exception e) {
			throw new IllegalStateException("Could not retrieve entity identities", e);
		}

		return entitySet;
	}

	public EntityTypeSetMapping getTypes() {
		return theTypes;
	}

	public <E> List<E> get(Class<E> type, boolean withSubTypes) {
		List<E> values = new ArrayList<>();
		forEach(type, withSubTypes, values::add);
		return values;
	}

	public <E> void forEach(Class<E> type, boolean withSubTypes, Consumer<? super E> forEach) {
		theEntities.descend(type, (key, value, match) -> {
			switch (match) {
			case SUB_TYPE:
				if (!withSubTypes)
					return false;
				//$FALL-THROUGH$
			case EXACT:
				if (value != null) {
					for (E entity : (Collection<E>) value)
						forEach.accept(entity);
				}
				break;
			case SUPER_TYPE:
				break;
			}
			return true;
		});
	}

	public boolean hasAny(Class<?> type) {
		boolean[] hasAny = new boolean[1];
		theEntities.descend(type, (key, value, match) -> {
			if (hasAny[0])
				return false;
			switch (match) {
			case SUPER_TYPE:
				break;
			case EXACT:
			case SUB_TYPE:
				if (value != null && !value.isEmpty()) {
					hasAny[0] = true;
					return false;
				}
				break;
			}
			return true;
		});
		return hasAny[0];
	}

	public <E> E getMin(Class<E> type) {
		EntityTypeMapping<E> entityType = (EntityTypeMapping<E>) theTypes.getEntityTypeHierarchy().get(type, TypeMatch.SUPER_TYPE);
		Object[] min = new Object[1];
		theEntities.descend(type, (key, value, match) -> {
			switch (match) {
			case SUPER_TYPE:
				break;
			case SUB_TYPE:
			case EXACT:
				if (value != null) {
					E typeMin = (E) value.peekFirst();
					if (typeMin == null) {// No values
					} else if (min[0] == null || entityType.getSorting().compare(typeMin, (E) min[0]) < 0)
						min[0] = typeMin;
				}
				break;
			}
			return true;
		});
		return (E) min[0];
	}

	public <E> E getMax(Class<E> type) {
		EntityTypeMapping<E> entityType = (EntityTypeMapping<E>) theTypes.getEntityTypeHierarchy().get(type, TypeMatch.SUPER_TYPE);
		Object[] max = new Object[1];
		theEntities.descend(type, (key, value, match) -> {
			switch (match) {
			case SUPER_TYPE:
				break;
			case SUB_TYPE:
			case EXACT:
				if (value != null) {
					E typeMax = (E) value.peekLast();
					if (typeMax == null) {// No values
					} else if (max[0] == null || entityType.getSorting().compare(typeMax, (E) max[0]) > 0)
						max[0] = typeMax;
				}
				break;
			}
			return true;
		});
		return (E) max[0];
	}

	public <E> E get(Class<E> type, Object... id) {
		EntityTypeMapping<E> typeMapping = (EntityTypeMapping<E>) theTypes.getEntityTypeHierarchy().get(type, TypeMatch.SUB_TYPE);
		if (typeMapping == null)
			throw new IllegalArgumentException("Unrecognized entity type: " + type);
		else if (id.length != typeMapping.getGenericType().getIdFields().size())
			throw new IllegalArgumentException("Entity type " + typeMapping.getName() + " (class " + type.getName() + ") has "
				+ typeMapping.getGenericType().getIdFields().size() + " ID fields: " + typeMapping.getGenericType().getIdFields() + ", not "
				+ id.length);
		Comparable<? super E> search = entity -> typeMapping.getSorting().compareId(id, entity);
		Object[] result = new Object[1];
		theEntities.descend(type, (key, value, match) -> {
			switch (match) {
			case EXACT:
			case SUB_TYPE:
				if (value != null) {
					Object found = ((BetterSortedSet<? extends E>) value).searchValue(search, SortedSearchFilter.OnlyMatch);
					if (found != null) {
						result[0] = found;
						return false;
					}
				}
				break;
			case SUPER_TYPE:
				break;
			}
			return true;
		});
		return (E) result[0];
	}

	public Collection<Object> getAll() {
		return IterableUtils.map(theEntities.entries(), Map.Entry::getValue);
	}

	public boolean diff(MappedEntitySet other, EntityDifferenceAccepter accepter) {
		boolean same = true;
		for (EntityTypeMapping<?> type : theTypes.getEntityTypes().values()) {
			if (!diffType(other, type, accepter))
				same = false;
		}
		return same;
	}

	private <E> boolean diffType(MappedEntitySet other, EntityTypeMapping<E> type, EntityDifferenceAccepter accepter) {
		BetterSortedSet<E> leftSet = (BetterSortedSet<E>) theEntities.get(type.getRealType(), TypeMatch.EXACT);
		BetterSortedSet<E> rightSet = (BetterSortedSet<E>) other.theEntities.get(type.getRealType(), TypeMatch.EXACT);
		boolean same = true;
		if (leftSet != null) {
			for (E leftEntity : leftSet) {
				E rightEntity = rightSet == null ? null : rightSet.getEquivalentValue(leftEntity, true);
				if (rightEntity != leftEntity) {
					same = false;
					accepter.differenceEncountered(type, leftEntity, rightEntity);
				}
			}
		}
		if (rightSet != null) {
			for (E rightEnity : rightSet) {
				if (leftSet != null && !leftSet.contains(rightEnity)) {
					same = false;
					accepter.differenceEncountered(type, null, rightEnity);
				}
			}
		}
		return same;
	}

	private <E> void addEntity(E entity, EntityTypeMapping<? super E> superType)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		EntityTypeMapping<E> type = getType(entity, superType);
		BetterSortedSet<E> entities = (BetterSortedSet<E>) theEntities.compute(type.getRealType(),
			__ -> BetterTreeSet.createTreeSet(type.getSorting()));
		entities.add(entity);
	}

	private <E> EntityTypeMapping<E> getType(E entity, EntityTypeMapping<? super E> superType) {
		EntityTypeMapping<E> type;
		if (superType != null && (superType.getRealType() == entity.getClass() || superType.getGenericType().getSubTypes().isEmpty()))
			type = (EntityTypeMapping<E>) superType;
		else {
			type = (EntityTypeMapping<E>) theTypes.getEntityTypeHierarchy().get(entity.getClass(), TypeMatch.SUPER_TYPE);
			if (type == null)
				throw new IllegalArgumentException(
					"Type " + entity.getClass().getName() + " of entity " + entity + " is not a recognized entity type");
		}
		return type;
	}

	private <E> void fillOut(E entity, EntityTypeMapping<?> superType, Map<Object, Boolean> filledOut)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (filledOut.put(entity, Boolean.TRUE) != null)
			return;
		addEntity(entity, (EntityTypeMapping<? super E>) superType);
		EntityTypeMapping<E> type = getType(entity, (EntityTypeMapping<? super E>) superType);
		for (EntityFieldMapping<?, ?> field : type.getFields()) {
			Object value = field.getGetter().invoke(entity);
			if (value == null) {// Nothing to do
			} else if (field.getGenericField().getType() instanceof EntityType)
				fillOut(value, theTypes.getEntityTypes().get(((EntityType) field.getGenericField().getType()).getName()), filledOut);
			else if (hasEntity(field.getGenericField().getType()))
				fillOutField(value, (FieldType.ParameterizedType<?>) field.getGenericField().getType(), filledOut);
		}
	}

	private void fillOutField(Object fieldValue, FieldType.ParameterizedType<?> type, Map<Object, Boolean> filledOut)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (type instanceof FieldType.CollectionType)
			fillOutCollection((Collection<?>) fieldValue, (FieldType.CollectionType<?, ?>) type, filledOut);
		else if (type instanceof FieldType.MapType)
			fillOutMap((Map<?, ?>) fieldValue, (FieldType.MapType<?, ?, ?>) type, filledOut);
		else if (type instanceof FieldType.MultiMapType)
			fillOutMultiMap((MultiMap<?, ?>) fieldValue, (FieldType.MultiMapType<?, ?, ?>) type, filledOut);
		else
			throw new IllegalStateException("Unhandled parameterized type " + type);
	}

	private <E> void fillOutCollection(Collection<?> fieldValue, FieldType.CollectionType<E, ?> type, Map<Object, Boolean> filledOut)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		for (Object value : fieldValue) {
			if (type.componentType instanceof EntityType)
				fillOut(value, theTypes.getEntityTypes().get(((EntityType) type.componentType).getName()), filledOut);
			else
				fillOutField(value, (FieldType.ParameterizedType<?>) type.componentType, filledOut);
		}
	}

	private <K, V> void fillOutMap(Map<?, ?> fieldValue, FieldType.MapType<K, V, ?> type, Map<Object, Boolean> filledOut)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		boolean keyEntity = hasEntity(type.keyType);
		boolean valueEntity = hasEntity(type.valueType);
		for (Map.Entry<?, ?> entry : fieldValue.entrySet()) {
			if (keyEntity) {
				if (type.keyType instanceof EntityType)
					fillOut(entry.getKey(), theTypes.getEntityTypes().get(((EntityType) type.keyType).getName()), filledOut);
				else
					fillOutField(entry.getKey(), (FieldType.ParameterizedType<?>) type.keyType, filledOut);
			}
			if (valueEntity) {
				if (type.valueType instanceof EntityType)
					fillOut(entry.getValue(), theTypes.getEntityTypes().get(((EntityType) type.valueType).getName()), filledOut);
				else
					fillOutField(entry.getValue(), (FieldType.ParameterizedType<?>) type.valueType, filledOut);
			}
		}
	}

	private <K, V> void fillOutMultiMap(MultiMap<?, ?> fieldValue, FieldType.MultiMapType<K, V, ?> type, Map<Object, Boolean> filledOut)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		boolean keyEntity = hasEntity(type.keyType);
		boolean valueEntity = hasEntity(type.valueType);
		for (MultiMap.MultiEntry<?, ?> entry : fieldValue.entrySet()) {
			if (keyEntity) {
				if (type.keyType instanceof EntityType)
					fillOut(entry.getKey(), theTypes.getEntityTypes().get(((EntityType) type.keyType).getName()), filledOut);
				else
					fillOutField(entry.getKey(), (FieldType.ParameterizedType<?>) type.keyType, filledOut);
			}
			if (valueEntity) {
				for (Object value : entry.getValues()) {
					if (type.valueType instanceof EntityType)
						fillOut(value, theTypes.getEntityTypes().get(((EntityType) type.valueType).getName()), filledOut);
					else
						fillOutField(value, (FieldType.ParameterizedType<?>) type.valueType, filledOut);
				}
			}
		}
	}

	private static boolean hasEntity(FieldType<?> type) {
		if (type instanceof EntityType)
			return true;
		else if (type instanceof FieldType.ParameterizedType) {
			for (FieldType<?> param : ((FieldType.ParameterizedType<?>) type).getTypeParameters()) {
				if (hasEntity(param))
					return true;
			}
			return false;
		} else
			return false;
	}
}
