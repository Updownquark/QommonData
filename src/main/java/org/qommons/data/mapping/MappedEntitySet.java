package org.qommons.data.mapping;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import org.qommons.ClassMap;
import org.qommons.ClassMap.TypeMatch;
import org.qommons.collect.BetterHashMap;
import org.qommons.collect.MultiMap;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.fn.TriConsumer;
import org.qommons.io.TextParseException;

public class MappedEntitySet {
	private static final ToIntFunction<Object> ARRAY_HASHER = (ToIntFunction<Object>) (ToIntFunction<?>) (ToIntFunction<Object[]>) Arrays::hashCode;
	private static final BiPredicate<Object, Object> ARRAY_EQUALS = (BiPredicate<Object, Object>) (BiPredicate<?, ?>) (BiPredicate<Object[], Object[]>) Arrays::equals;

	private static Map<Object, Object> createEntityMap(EntityType type) {
		if (type.getIdFields().size() == 1)
			return new HashMap<>();
		else
			return BetterHashMap.build()//
				.withEquivalence(ARRAY_HASHER, ARRAY_EQUALS)//
				.build();
	}

	private static Object toHashId(Object[] id) {
		if (id.length == 1)
			return id[0];
		else
			return id;
	}

	private final EntityTypeSetMapping theTypes;
	private final ClassMap<Map<Object, Object>> theEntities;

	private MappedEntitySet(EntityTypeSetMapping types, ClassMap<Map<Object, Object>> entities) {
		theTypes = types;
		theEntities = entities;
	}

	public static MappedEntitySet create(GenericEntitySet data, EntityTypeSetMapping mappedTypes,
		BiFunction<EntityTypeMapping<?>, GenericEntity, ?> entityCreator,
		TriConsumer<Object, GenericEntity, MappedEntitySet> entityInitializer) throws IOException, TextParseException {
		ClassMap<Map<Object, Object>> entities = new ClassMap<>();
		for (EntityTypeMapping<?> type : mappedTypes.getEntityTypes().values()) {
			if (type.getGenericType().getSuperType() != null)
				continue;
			for (GenericEntity entity : data.getEntities(type.getName())) {
				EntityTypeMapping<?> entityType;
				if (entity.getType() == type.getGenericType())
					entityType = type;
				else
					entityType = mappedTypes.getEntityTypes().get(entity.getType().getName());
				entities.computeIfAbsent(entityType.getRealType(), () -> createEntityMap(type.getGenericType())).put(
					toHashId(entity.getId()), //
					entityCreator.apply(entityType, entity));
			}
		}
		return new MappedEntitySet(mappedTypes, entities);
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
					for (E entity : (Collection<E>) value.values())
						forEach.accept(entity);
				}
				break;
			case SUPER_TYPE:
				break;
			}
			return true;
		});
	}

	public <E> E get(Class<E> type, Object... id) {
		Object hashId;
		EntityTypeMapping<?> typeMapping = theTypes.getEntityTypeHierarchy().get(type, TypeMatch.SUB_TYPE);
		if (typeMapping == null)
			throw new IllegalArgumentException("Unrecognized entity type: " + type);
		else if (id.length != typeMapping.getGenericType().getIdFields().size())
			throw new IllegalArgumentException("Entity type " + typeMapping.getName() + " (class " + type.getName() + ") has "
				+ typeMapping.getGenericType().getIdFields().size() + " ID fields: " + typeMapping.getGenericType().getIdFields() + ", not "
				+ id.length);
		else if (id.length == 1)
			hashId = id[0];
		else
			hashId = id;
		Object[] result = new Object[1];
		theEntities.descend(type, (key, value, match) -> {
			switch (match) {
			case EXACT:
			case SUB_TYPE:
				if (value != null) {
					Object found = value.get(hashId);
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

	private void addEntity(Object entity, EntityTypeMapping<?> superType)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		EntityTypeMapping<?> type = getType(entity, superType);
		theEntities.compute(type.getRealType(), __ -> createEntityMap(superType.getGenericType())).putIfAbsent(getId(entity, type), entity);
	}

	private Object getId(Object entity, EntityTypeMapping<?> type)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (type.getIdFields().size() == 1)
			return type.getIdFields().getFirst().getGetter().invoke(entity);
		Object[] id = new Object[type.getGenericType().getIdFields().size()];
		int i = 0;
		for (EntityFieldMapping<?, ?> field : type.getIdFields()) {
			id[i++] = field.getGetter().invoke(entity);
		}
		return id;
	}

	private EntityTypeMapping<?> getType(Object entity, EntityTypeMapping<?> superType) {
		EntityTypeMapping<?> type;
		if (superType != null && (superType.getRealType() == entity.getClass() || superType.getGenericType().getSubTypes().isEmpty()))
			type = superType;
		else {
			type = theTypes.getEntityTypeHierarchy().get(entity.getClass(), TypeMatch.SUPER_TYPE);
			if (type == null)
				throw new IllegalArgumentException(
					"Type " + entity.getClass().getName() + " of entity " + entity + " is not a recognized entity type");
		}
		return type;
	}

	private void fillOut(Object entity, EntityTypeMapping<?> superType, Map<Object, Boolean> filledOut)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (filledOut.put(entity, Boolean.TRUE) != null)
			return;
		addEntity(entity, superType);
		EntityTypeMapping<?> type = getType(entity, superType);
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
