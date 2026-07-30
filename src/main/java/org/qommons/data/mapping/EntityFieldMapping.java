package org.qommons.data.mapping;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;

import org.qommons.Named;
import org.qommons.collect.MultiMap;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.fn.FunctionUtils;

public class EntityFieldMapping<G, R> implements Named {
	private final EntityTypeMapping<?> theOwner;
	private final EntityField<G> theGenericField;
	private final Method theGetter;
	private final FieldValueMapper<G, R> theMapper;
	private Comparator<? super R> theSorting;

	public EntityFieldMapping(EntityTypeMapping<?> owner, EntityField<G> genericField, Method getter, EntityTypeSetMapping types) {
		theOwner = owner;
		theGenericField = genericField;
		theGetter = getter;
		theMapper = createMapper(genericField.getType(), getter.getGenericReturnType());
	}

	void init() {
		theSorting = (Comparator<? super R>) sortRealValues(theGenericField.getType(), theOwner.getTypeSet());
	}

	public EntityTypeMapping<?> getOwner() {
		return theOwner;
	}

	public EntityField<G> getGenericField() {
		return theGenericField;
	}

	public Method getGetter() {
		return theGetter;
	}

	@Override
	public String getName() {
		return theGenericField.getName();
	}

	public Comparator<? super R> getSorting() {
		return theSorting;
	}

	public R map(G genericValue, R emptyValue, MappedEntitySet entitySet) {
		return theMapper.map(genericValue, emptyValue, entitySet);
	}

	@Override
	public String toString() {
		return theGenericField.toString();
	}

	private interface FieldValueMapper<G, R> {
		R map(G genericValue, R emptyValue, MappedEntitySet entitySet);
	}

	private static <G, R> FieldValueMapper<G, R> createMapper(FieldType<G> genericType, Type realType) {
		if (genericType instanceof FieldType.SimpleType || genericType == FieldType.BLOB || genericType instanceof FieldType.TupleType)
			return (FieldValueMapper<G, R>) SimpleFieldMapper.INSTANCE;
		else if (genericType instanceof EnumType)
			return (FieldValueMapper<G, R>) new EnumFieldMapper<>((Class<?>) realType);
		else if (genericType instanceof EntityType)
			return (FieldValueMapper<G, R>) new EntityFieldMapper<>((Class<R>) realType);
		else if (genericType instanceof FieldType.CollectionType)
			return (FieldValueMapper<G, R>) new CollectionFieldMapper<>(createMapper(
				((FieldType.CollectionType<?, ?>) genericType).componentType, ((ParameterizedType) realType).getActualTypeArguments()[0]));
		else if (genericType instanceof FieldType.MapType) {
			FieldType.MapType<?, ?, ?> mapType = (FieldType.MapType<?, ?, ?>) genericType;
			ParameterizedType realMapType = (ParameterizedType) realType;
			return (FieldValueMapper<G, R>) new MapFieldMapper<>(//
				createMapper(mapType.keyType, realMapType.getActualTypeArguments()[0]), //
				createMapper(mapType.valueType, realMapType.getActualTypeArguments()[0]));
		} else if (genericType instanceof FieldType.MultiMapType) {
			FieldType.MultiMapType<?, ?, ?> mapType = (FieldType.MultiMapType<?, ?, ?>) genericType;
			ParameterizedType realMapType = (ParameterizedType) realType;
			return (FieldValueMapper<G, R>) new MultiMapFieldMapper<>(//
				createMapper(mapType.keyType, realMapType.getActualTypeArguments()[0]), //
				createMapper(mapType.valueType, realMapType.getActualTypeArguments()[0]));
		} else
			throw new IllegalStateException("Unrecognized field type: " + genericType);
	}

	static class SimpleFieldMapper<T> implements FieldValueMapper<T, T> {
		static final SimpleFieldMapper<?> INSTANCE = new SimpleFieldMapper<>();

		@Override
		public T map(T genericValue, T emptyValue, MappedEntitySet entitySet) {
			return genericValue;
		}
	}

	static class EnumFieldMapper<E extends Enum<E>> implements FieldValueMapper<EnumValue, E> {
		private final Class<E> theType;

		public EnumFieldMapper(Class<?> type) {
			this.theType = (Class<E>) type;
		}

		@Override
		public E map(EnumValue genericValue, E emptyValue, MappedEntitySet entitySet) {
			return genericValue == null ? null : Enum.valueOf(theType, genericValue.getName());
		}
	}

	static class EntityFieldMapper<E> implements FieldValueMapper<GenericEntity, E> {
		private final Class<E> theType;

		EntityFieldMapper(Class<E> type) {
			theType = type;
		}

		@Override
		public E map(GenericEntity genericValue, E emptyValue, MappedEntitySet entitySet) {
			return genericValue == null ? null : entitySet.get(theType, genericValue.getId());
		}
	}

	static class CollectionFieldMapper<GE, RE, GC extends Collection<GE>, RC extends Collection<RE>> implements FieldValueMapper<GC, RC> {
		private final FieldValueMapper<GE, RE> theElementMapper;

		CollectionFieldMapper(FieldValueMapper<GE, RE> elementMapper) {
			theElementMapper = elementMapper;
		}

		@Override
		public RC map(GC genericValue, RC emptyValue, MappedEntitySet entitySet) {
			if (emptyValue == null)
				throw new IllegalArgumentException("For collection types, an empty collection must be provided");
			for (GE value : genericValue)
				emptyValue.add(theElementMapper.map(value, null, entitySet));
			return emptyValue;
		}
	}

	static class MapFieldMapper<GK, RK, GV, RV, GM extends Map<GK, GV>, RM extends Map<RK, RV>> implements FieldValueMapper<GM, RM> {
		private final FieldValueMapper<GK, RK> theKeyMapper;
		private final FieldValueMapper<GV, RV> theValueMapper;

		MapFieldMapper(FieldValueMapper<GK, RK> keyMapper, FieldValueMapper<GV, RV> valueMapper) {
			theKeyMapper = keyMapper;
			theValueMapper = valueMapper;
		}

		@Override
		public RM map(GM genericValue, RM emptyValue, MappedEntitySet entitySet) {
			if (emptyValue == null)
				throw new IllegalArgumentException("For collection types, an empty collection must be provided");
			for (Map.Entry<GK, GV> entry : genericValue.entrySet())
				emptyValue.put(//
					theKeyMapper.map(entry.getKey(), null, entitySet), //
					theValueMapper.map(entry.getValue(), null, entitySet));
			return emptyValue;
		}
	}

	static class MultiMapFieldMapper<GK, RK, GV, RV, GM extends MultiMap<GK, GV>, RM extends MultiMap<RK, RV>>
	implements FieldValueMapper<GM, RM> {
		private final FieldValueMapper<GK, RK> theKeyMapper;
		private final FieldValueMapper<GV, RV> theValueMapper;

		MultiMapFieldMapper(FieldValueMapper<GK, RK> keyMapper, FieldValueMapper<GV, RV> valueMapper) {
			theKeyMapper = keyMapper;
			theValueMapper = valueMapper;
		}

		@Override
		public RM map(GM genericValue, RM emptyValue, MappedEntitySet entitySet) {
			if (emptyValue == null)
				throw new IllegalArgumentException("For collection types, an empty collection must be provided");
			for (MultiMap.MultiEntry<GK, GV> entry : genericValue.entrySet()) {
				for (GV value : entry.getValues())
					emptyValue.add(//
						theKeyMapper.map(entry.getKey(), null, entitySet), //
						theValueMapper.map(value, null, entitySet));
			}
			return emptyValue;
		}
	}

	public static Comparator<?> sortRealValues(FieldType<?> type, EntityTypeSetMapping types) {
		if (type instanceof FieldType.SimpleType || type == FieldType.BLOB)
			return type;
		else if (type instanceof EntityType)
			return types.getEntityTypes().get(((EntityType) type).getName()).getSorting();
		else if (type instanceof EnumType)
			return FunctionUtils.COMPARABLE_COMPARE;
		else if (type instanceof FieldType.CollectionType)
			return new CollectionSort<>(sortRealValues(((FieldType.CollectionType<?, ?>) type).componentType, types));
		else if (type instanceof FieldType.MapType)
			return new MapSort<>(//
				sortRealValues(((FieldType.MapType<?, ?, ?>) type).keyType, types),
				sortRealValues(((FieldType.MapType<?, ?, ?>) type).valueType, types));
		else if (type instanceof FieldType.MultiMapType)
			return new MultiMapSort<>(//
				sortRealValues(((FieldType.MapType<?, ?, ?>) type).keyType, types),
				sortRealValues(((FieldType.MapType<?, ?, ?>) type).valueType, types));
		else
			throw new IllegalStateException("Unrecognized field type: " + type);
	}

	static class CollectionSort<E> implements Comparator<Collection<? extends E>> {
		private final Comparator<? super E> theElementSort;

		CollectionSort(Comparator<? super E> elementSort) {
			theElementSort = elementSort;
		}

		@Override
		public int compare(Collection<? extends E> o1, Collection<? extends E> o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			Iterator<? extends E> iter1 = o1.iterator();
			Iterator<? extends E> iter2 = o2.iterator();
			while (iter1.hasNext()) {
				if (iter2.hasNext()) {
					int comp = theElementSort.compare(iter1.next(), iter2.next());
					if (comp != 0)
						return comp;
				} else
					return 11;
			}
			if (iter2.hasNext())
				return -1;
			return 0;
		}
	}

	static class MapSort<K, V> implements Comparator<Map<? extends K, ? extends V>> {
		private final Comparator<? super K> theKeySort;
		private final Comparator<? super V> theValueSort;

		MapSort(Comparator<? super K> keySort, Comparator<? super V> valueSort) {
			theKeySort = keySort;
			theValueSort = valueSort;
		}

		@Override
		public int compare(Map<? extends K, ? extends V> o1, Map<? extends K, ? extends V> o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			Iterator<? extends Map.Entry<? extends K, ? extends V>> iter1 = o1.entrySet().iterator();
			Iterator<? extends Map.Entry<? extends K, ? extends V>> iter2 = o2.entrySet().iterator();
			while (iter1.hasNext()) {
				if (iter2.hasNext()) {
					Map.Entry<? extends K, ? extends V> entry1 = iter1.next();
					Map.Entry<? extends K, ? extends V> entry2 = iter2.next();
					int comp = theKeySort.compare(entry1.getKey(), entry2.getKey());
					if (comp == 0)
						comp = theValueSort.compare(entry1.getValue(), entry2.getValue());
					if (comp != 0)
						return comp;
				} else
					return 1;
			}
			if (iter2.hasNext())
				return -1;
			return 0;
		}
	}

	static class MultiMapSort<K, V> implements Comparator<MultiMap<? extends K, ? extends V>> {
		private final Comparator<? super K> theKeySort;
		private final Comparator<? super V> theValueSort;

		MultiMapSort(Comparator<? super K> keySort, Comparator<? super V> valueSort) {
			theKeySort = keySort;
			theValueSort = valueSort;
		}

		@Override
		public int compare(MultiMap<? extends K, ? extends V> o1, MultiMap<? extends K, ? extends V> o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			Iterator<? extends MultiMap.MultiEntry<? extends K, ? extends V>> iter1 = o1.entrySet().iterator();
			Iterator<? extends MultiMap.MultiEntry<? extends K, ? extends V>> iter2 = o2.entrySet().iterator();
			while (iter1.hasNext()) {
				if (iter2.hasNext()) {
					MultiMap.MultiEntry<? extends K, ? extends V> entry1 = iter1.next();
					MultiMap.MultiEntry<? extends K, ? extends V> entry2 = iter2.next();
					int comp = theKeySort.compare(entry1.getKey(), entry2.getKey());
					if (comp == 0) {
						Iterator<? extends V> valueIter1 = entry1.getValues().iterator();
						Iterator<? extends V> valueIter2 = entry2.getValues().iterator();
						while (comp == 0 && valueIter1.hasNext()) {
							if (valueIter2.hasNext())
								comp = theValueSort.compare(valueIter1.next(), valueIter2.next());
							else
								comp = 1;
						}
						if (valueIter2.hasNext())
							comp = -1;
					}
					if (comp != 0)
						return comp;
				} else
					return 1;
			}
			if (iter2.hasNext())
				return -1;
			return 0;
		}
	}
}
