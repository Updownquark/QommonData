package org.qommons.data.types;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.qommons.QommonsUtils;
import org.qommons.StringUtils;
import org.qommons.TimeUtils;
import org.qommons.collect.BetterCollection;
import org.qommons.collect.BetterHashMap;
import org.qommons.collect.BetterHashMultiMap;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterList;
import org.qommons.collect.BetterMap;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSet;
import org.qommons.collect.BetterSortedList;
import org.qommons.collect.BetterSortedMap;
import org.qommons.collect.BetterSortedMultiMap;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.DequeList;
import org.qommons.collect.MultiEntryHandle;
import org.qommons.collect.MultiMap;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.ex.ExFunction;
import org.qommons.io.LocatedFilePosition;
import org.qommons.tree.BetterTreeList;
import org.qommons.tree.BetterTreeMap;
import org.qommons.tree.BetterTreeMultiMap;
import org.qommons.tree.BetterTreeSet;
import org.qommons.tree.SortedTreeList;

public interface FieldType<F> extends Comparator<F> {
	/**
	 * A placeholder to indicate that an ID field for a newly created entity type should be a reference to another instance of the same type
	 */
	public static final SelfReferenceType SELF = SelfReferenceType.INSTANCE;

	public static final FieldType<Blob> BLOB = new BlobType();

	default boolean isValidKey() {
		return true;
	}

	default boolean isSortable() {
		return true;
	}

	boolean isInstance(Object value);

	boolean isAssignableFrom(FieldType<?> other);

	F convert(Object value, FieldType<?> valueType);

	<FT extends FieldType<?>> FT containsTypeLike(Function<? super FieldType<?>, FT> test);

	/**
	 * A placeholder type to indicate that an ID field for a newly created entity type should be a reference to another instance of the same
	 * type
	 */
	public static class SelfReferenceType implements FieldType<Void> {
		public static final SelfReferenceType INSTANCE = new SelfReferenceType();

		private SelfReferenceType() {
		}

		@Override
		public boolean isInstance(Object value) {
			return false;
		}

		@Override
		public int compare(Void o1, Void o2) {
			throw new IllegalStateException();
		}

		@Override
		public boolean isAssignableFrom(FieldType<?> other) {
			return other == this;
		}

		@Override
		public Void convert(Object value, FieldType<?> valueType) {
			return null;
		}

		@Override
		public <FT extends FieldType<?>> FT containsTypeLike(Function<? super FieldType<?>, FT> test) {
			return test.apply(this);
		}
	}

	public static class SimpleType<F> implements FieldType<F> {
		private static final Map<Class<?>, FieldType<?>> SIMPLE_TYPES = new HashMap<>();

		private static <T> SimpleType<T> add(SimpleType<T> type, Class<T> other) {
			SIMPLE_TYPES.put(type.type, type);
			if (other != null)
				SIMPLE_TYPES.put(other, type);
			return type;
		}

		public static <T> SimpleType<T> get(Class<T> type) {
			return (SimpleType<T>) SIMPLE_TYPES.get(type);
		}

		public static final SimpleType<Boolean> BOOLEAN = new SimpleType<>(Boolean.class, boolean.class);
		public static final SimpleType<Character> CHAR = new SimpleType<>(Character.class, char.class);
		public static final SimpleType<Byte> BYTE = new SimpleType<>(Byte.class, byte.class);
		public static final SimpleType<Short> SHORT = new SimpleType<>(Short.class, short.class);
		public static final SimpleType<Integer> INT = new SimpleType<>(Integer.class, int.class);
		public static final SimpleType<Long> LONG = new SimpleType<>(Long.class, long.class);
		public static final SimpleType<Float> FLOAT = new SimpleType<>(Float.class, float.class);
		public static final SimpleType<Double> DOUBLE = new SimpleType<>(Double.class, double.class);
		public static final SimpleType<String> STRING = new SimpleType<>(String.class, null);
		public static final SimpleType<Instant> INSTANT = new SimpleType<>(Instant.class, null);
		public static final SimpleType<Duration> DURATION = new SimpleType<>(Duration.class, null);

		private static final DateTimeFormatter INSTANT_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss[.nnnnnnnnn]")//
			.withZone(ZoneId.of("GMT"));

		public final Class<F> type;
		public final Class<F> primitiveType;

		private SimpleType(Class<F> type, Class<F> primitive) {
			this.type = type;
			this.primitiveType = primitive;
			SIMPLE_TYPES.put(type, this);
			if (primitive != null)
				SIMPLE_TYPES.put(primitive, this);
		}

		@Override
		public boolean isInstance(Object value) {
			return type.isInstance(value);
		}

		@Override
		public int compare(Object o1, Object o2) {
			if (this == STRING)
				return StringUtils.compareNumberTolerant((String) o1, (String) o2, true, true);
			else
				return ((Comparable<Object>) o1).compareTo(o2);
		}

		@Override
		public boolean isAssignableFrom(FieldType<?> other) {
			if (this == other)
				return true;
			else if (this == STRING)
				return other == CHAR;
			else if (this == DOUBLE)
				return other == FLOAT || other == LONG || other == INT || other == SHORT || other == BYTE || other == CHAR;
			else if (other == FLOAT)
				return other == LONG || other == INT || other == SHORT || other == BYTE || other == CHAR;
			else if (this == LONG)
				return other == INT || other == SHORT || other == BYTE || other == CHAR;
			else if (this == INT)
				return other == SHORT || other == BYTE || other == CHAR;
			else if (this == SHORT)
				return other == BYTE;
			else
				return false;
		}

		@Override
		public F convert(Object value, FieldType<?> valueType) {
			if (this == valueType)
				return (F) value;
			else if (this == STRING) {
				if (valueType == CHAR)
					return (F) String.valueOf(((Character) value).charValue());
			} else if (this == DOUBLE) {
				if (valueType == CHAR)
					return (F) Double.valueOf(((Character) value).charValue());
				else
					return (F) Double.valueOf(((Number) value).doubleValue());
			} else if (this == FLOAT) {
				if (valueType == CHAR)
					return (F) Float.valueOf(((Character) value).charValue());
				else
					return (F) Float.valueOf(((Number) value).floatValue());
			} else if (this == LONG) {
				if (valueType == CHAR)
					return (F) Long.valueOf(((Character) value).charValue());
				else
					return (F) Long.valueOf(((Number) value).longValue());
			} else if (this == INT) {
				if (valueType == CHAR)
					return (F) Integer.valueOf(((Character) value).charValue());
				else
					return (F) Integer.valueOf(((Number) value).intValue());
			} else if (this == SHORT) {
				return (F) Short.valueOf(((Number) value).shortValue());
			}
			throw new IllegalStateException("Unrecognized conversion from " + this + " to " + valueType);
		}

		@Override
		public <FT extends FieldType<?>> FT containsTypeLike(Function<? super FieldType<?>, FT> test) {
			return test.apply(this);
		}

		public F parse(String text, IntFunction<LocatedFilePosition> source) throws QonfigInterpretationException {
			if (this == BOOLEAN) {
				switch (text) {
				case "true":
				case "TRUE":
					return (F) Boolean.TRUE;
				case "false":
				case "FALSE":
					return (F) Boolean.FALSE;
				default:
					throw new QonfigInterpretationException("Expected 'true' or 'false', not '" + text + "'", source.apply(0),
						text.length());
				}
			} else if (this == CHAR) {
				if (text.length() != 1)
					throw new QonfigInterpretationException("Expected a single character", source.apply(0), text.length());
				return (F) Character.valueOf(text.charAt(0));
			} else if (this == STRING) {
				return (F) text;
			} else if (this == BYTE) {
				try {
					return (F) Byte.valueOf(text.toString());
				} catch (NumberFormatException e) {
					throw new QonfigInterpretationException("Could not parse byte from '" + text + "'", source.apply(0), text.length(), e);
				}
			} else if (this == SHORT) {
				try {
					return (F) Short.valueOf(text.toString());
				} catch (NumberFormatException e) {
					throw new QonfigInterpretationException("Could not parse short from '" + text + "'", source.apply(0), text.length(), e);
				}
			} else if (this == INT) {
				try {
					return (F) Integer.valueOf(text.toString());
				} catch (NumberFormatException e) {
					throw new QonfigInterpretationException("Could not parse int from '" + text + "'", source.apply(0), text.length(), e);
				}
			} else if (this == LONG) {
				try {
					return (F) Long.valueOf(text.toString());
				} catch (NumberFormatException e) {
					throw new QonfigInterpretationException("Could not parse long from '" + text + "'", source.apply(0), text.length(), e);
				}
			} else if (this == FLOAT) {
				try {
					return (F) Float.valueOf(text.toString());
				} catch (NumberFormatException e) {
					throw new QonfigInterpretationException("Could not parse float from '" + text + "'", source.apply(0), text.length(), e);
				}
			} else if (this == DOUBLE) {
				try {
					return (F) Double.valueOf(text.toString());
				} catch (NumberFormatException e) {
					throw new QonfigInterpretationException("Could not parse double from '" + text + "'", source.apply(0), text.length(),
						e);
				}
			} else if (this == INSTANT) {
				try {
					LocalDateTime localTime = LocalDateTime.from(INSTANT_FORMAT.parse(text));
					return (F) localTime.atOffset(ZoneOffset.UTC).toInstant();
				} catch (DateTimeParseException e) {
					throw new QonfigInterpretationException("Could not parse instant from '" + text + "'", source.apply(0), text.length(),
						e);
				}
			} else if (this == DURATION) {
				double seconds;
				try {
					seconds = Double.parseDouble(text.toString());
				} catch (NumberFormatException e) {
					throw new QonfigInterpretationException("Could not parse double from '" + text + "'", source.apply(0), text.length(),
						e);
				}
				return (F) TimeUtils.ofSeconds(seconds);
			} else {
				throw new IllegalStateException("Who even am I?");
			}
		}

		public void print(StringBuilder str, F value) {
			if (this == INSTANT) {
				INSTANT_FORMAT.formatTo((Instant) value, str);
			} else if (this == DURATION) {
				str.append(TimeUtils.toSeconds((Duration) value));
			} else
				str.append(value);
		}

		@Override
		public int hashCode() {
			return type.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof SimpleType && type.equals(((SimpleType<?>) obj).type);
		}

		@Override
		public String toString() {
			if (primitiveType != null)
				return primitiveType.getName();
			else
				return type.getSimpleName();
		}
	}

	public static interface ParameterizedType<F> extends FieldType<F> {
		List<FieldType<?>> getTypeParameters();

		default boolean isComplex() {
			for (FieldType<?> param : getTypeParameters()) {
				if (param instanceof ParameterizedType)
					return true;
			}
			return false;
		}

		boolean rawTypesEqual(ParameterizedType<?> other);

		@Override
		default <FT extends FieldType<?>> FT containsTypeLike(Function<? super FieldType<?>, FT> test) {
			FT me = test.apply(this);
			if (me != null)
				return me;
			for (FieldType<?> param : getTypeParameters()) {
				FT found = param.containsTypeLike(test);
				if (found != null)
					return found;
			}
			return null;
		}

		<X extends Throwable> ParameterizedType<F> map(ExFunction<? super FieldType<?>, ? extends FieldType<?>, X> map) throws X;

		F createEmptyStructure();
	}

	public static class CollectionType<E, C extends BetterCollection<E>> implements ParameterizedType<C> {
		public final FieldType<E> componentType;
		public final boolean isSorted;
		public final boolean isDistinct;

		public CollectionType(FieldType<E> componentType, boolean sorted, boolean distinct) {
			this.componentType = componentType;
			this.isSorted = sorted;
			this.isDistinct = distinct;
		}

		@Override
		public List<FieldType<?>> getTypeParameters() {
			return Collections.singletonList(componentType);
		}

		@Override
		public boolean rawTypesEqual(ParameterizedType<?> other) {
			if (!(other instanceof CollectionType))
				return false;
			CollectionType<?, ?> ct = (CollectionType<?, ?>) other;
			return isSorted == ct.isSorted && isDistinct == ct.isDistinct;
		}

		@Override
		public boolean isInstance(Object value) {
			if (isSorted) {
				if (isDistinct) {
					if (!(value instanceof BetterSortedSet))
						return false;
				} else if (!(value instanceof BetterSortedList))
					return false;
			} else if (isDistinct) {
				if (!(value instanceof BetterSet))
					return false;
			} else if (!(value instanceof BetterList))
				return false;
			for (Object v : (Collection<?>) value) {
				if (!componentType.isInstance(v))
					return false;
			}
			return true;
		}

		@Override
		public int compare(C o1, C o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			Iterator<E> iter1 = o1.iterator();
			Iterator<E> iter2 = o2.iterator();
			while (iter1.hasNext()) {
				if (!iter2.hasNext())
					return 1;
				int comp = componentType.compare(iter1.next(), iter2.next());
				if (comp != 0)
					return comp;
			}
			if (iter1.hasNext())
				return -1;
			return 0;
		}

		@Override
		public boolean isAssignableFrom(FieldType<?> other) {
			if (!(other instanceof CollectionType))
				return false;
			CollectionType<?, ?> ct = (CollectionType<?, ?>) other;
			if (isDistinct && !ct.isDistinct)
				return false;
			else
				return componentType.isAssignableFrom(ct.componentType);
		}

		@Override
		public C createEmptyStructure() {
			return createEmptyCollection(componentType);
		}

		public C createEmptyCollection(Comparator<? super E> sorting) {
			if (isSorted) {
				if (isDistinct)
					return (C) BetterTreeSet.createTreeSet(sorting);
				else
					return (C) SortedTreeList.createTreeList(sorting);
			} else if (isDistinct)
				return (C) BetterHashSet.create();
			else
				return (C) BetterTreeList.create();
		}

		@Override
		public C convert(Object value, FieldType<?> valueType) {
			CollectionType<?, ?> ct = (CollectionType<?, ?>) valueType;
			if (isSorted == ct.isSorted && isDistinct == ct.isDistinct && componentType.equals(ct.componentType))
				return (C) value;
			C newValue = createEmptyStructure();
			for (Object v : (Collection<?>) value)
				newValue.add(componentType.convert(v, ct.componentType));
			return null;
		}

		@Override
		public <X extends Throwable> CollectionType<E, C> map(ExFunction<? super FieldType<?>, ? extends FieldType<?>, X> map) throws X {
			FieldType<E> newCT = (FieldType<E>) map.apply(componentType);
			return newCT == componentType ? this : new CollectionType<>(newCT, isSorted, isDistinct);
		}

		@Override
		public int hashCode() {
			return Objects.hash(componentType, isSorted, isDistinct);
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof CollectionType))
				return false;
			CollectionType<?, ?> other = (CollectionType<?, ?>) obj;
			return componentType.equals(other.componentType) && isSorted == other.isSorted && isDistinct == other.isDistinct;
		}

		@Override
		public String toString() {
			StringBuilder str = new StringBuilder();
			if (isSorted) {
				if (isDistinct)
					str.append("SortedSet");
				else
					str.append("SortedList");
			} else if (isDistinct)
				str.append("Set");
			else
				str.append("List");
			return str.append('<').append(componentType).append('>').toString();
		}
	}

	public static class MapType<K, V, M extends BetterMap<K, V>> implements ParameterizedType<M> {
		public final FieldType<K> keyType;
		public final FieldType<V> valueType;
		public final boolean isSorted;

		public MapType(FieldType<K> keyType, FieldType<V> valueType, boolean sorted) {
			this.keyType = keyType;
			this.valueType = valueType;
			isSorted = sorted;
		}

		@Override
		public List<FieldType<?>> getTypeParameters() {
			return DequeList.of(keyType, valueType);
		}

		@Override
		public boolean rawTypesEqual(ParameterizedType<?> other) {
			if (!(other instanceof MapType))
				return false;
			MapType<?, ?, ?> ct = (MapType<?, ?, ?>) other;
			return isSorted == ct.isSorted;
		}

		@Override
		public boolean isInstance(Object value) {
			if (isSorted) {
				if (!(value instanceof BetterSortedMap))
					return false;
			} else if (!(value instanceof BetterMap))
				return false;
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				if (!keyType.isInstance(entry.getKey()) || !valueType.isInstance(entry.getValue()))
					return false;
			}
			return true;
		}

		@Override
		public int compare(M o1, M o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			Iterator<Map.Entry<K, V>> iter1 = o1.entrySet().iterator();
			Iterator<Map.Entry<K, V>> iter2 = o2.entrySet().iterator();
			while (iter1.hasNext()) {
				if (!iter2.hasNext())
					return 1;
				Map.Entry<K, V> e1 = iter1.next();
				Map.Entry<K, V> e2 = iter2.next();
				int comp = keyType.compare(e1.getKey(), e2.getKey());
				if (comp != 0)
					return comp;
				comp = valueType.compare(e1.getValue(), e2.getValue());
				if (comp != 0)
					return comp;
			}
			if (iter1.hasNext())
				return -1;
			return 0;
		}

		@Override
		public boolean isAssignableFrom(FieldType<?> other) {
			if (!(other instanceof MapType))
				return false;
			MapType<?, ?, ?> mt = (MapType<?, ?, ?>) other;
			return keyType.isAssignableFrom(mt.keyType)//
				&& valueType.isAssignableFrom(mt.valueType);
		}

		@Override
		public M createEmptyStructure() {
			if (isSorted)
				return (M) BetterTreeMap.create(keyType);
			else
				return (M) BetterHashMap.create();
		}

		@Override
		public M convert(Object value, FieldType<?> otherType) {
			if (equals(otherType))
				return (M) value;
			MapType<?, ?, ?> mt = (MapType<?, ?, ?>) otherType;
			M newValue = createEmptyStructure();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				newValue.put(keyType.convert(entry.getKey(), mt.keyType), valueType.convert(entry.getValue(), mt.valueType));
			}
			return newValue;
		}

		@Override
		public <X extends Throwable> MapType<K, V, M> map(ExFunction<? super FieldType<?>, ? extends FieldType<?>, X> map) throws X {
			FieldType<K> newKT = (FieldType<K>) map.apply(keyType);
			FieldType<V> newVT = (FieldType<V>) map.apply(valueType);
			if (newKT == keyType && newVT == valueType)
				return this;
			else
				return new MapType<>(keyType, valueType, isSorted);
		}

		@Override
		public int hashCode() {
			return Objects.hash(keyType, valueType, isSorted);
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof MapType))
				return false;
			MapType<?, ?, ?> other = (MapType<?, ?, ?>) obj;
			return keyType.equals(other.keyType) && valueType.equals(other.valueType) && isSorted == other.isSorted;
		}

		@Override
		public String toString() {
			StringBuilder str = new StringBuilder();
			if (isSorted)
				str.append("Sorted");
			str.append("Map");
			return str.append('<').append(keyType).append(", ").append(valueType).append('>').toString();
		}
	}

	public static class MultiMapType<K, V, M extends BetterMultiMap<K, V>> implements ParameterizedType<M> {
		public final FieldType<K> keyType;
		public final FieldType<V> valueType;
		public final boolean isSorted;

		public MultiMapType(FieldType<K> keyType, FieldType<V> valueType, boolean sorted) {
			this.keyType = keyType;
			this.valueType = valueType;
			isSorted = sorted;
		}

		@Override
		public List<FieldType<?>> getTypeParameters() {
			return DequeList.of(keyType, valueType);
		}

		@Override
		public boolean rawTypesEqual(ParameterizedType<?> other) {
			if (!(other instanceof MultiMapType))
				return false;
			MultiMapType<?, ?, ?> ct = (MultiMapType<?, ?, ?>) other;
			return isSorted == ct.isSorted;
		}

		@Override
		public boolean isInstance(Object value) {
			if (isSorted) {
				if (!(value instanceof BetterSortedMultiMap))
					return false;
			} else {
				if (!(value instanceof BetterMultiMap))
					return false;
			}
			for (MultiEntryHandle<?, ?> entry : ((BetterMultiMap<?, ?>) value).entrySet()) {
				if (!keyType.isInstance(entry.getKey()))
					return false;
				for (Object v : entry.getValues()) {
					if (!valueType.isInstance(v))
						return false;
				}
			}
			return true;
		}

		@Override
		public int compare(M o1, M o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			Iterator<? extends MultiMap.MultiEntry<K, V>> iter1 = o1.entrySet().iterator();
			Iterator<? extends MultiMap.MultiEntry<K, V>> iter2 = o2.entrySet().iterator();
			while (iter1.hasNext()) {
				if (!iter2.hasNext())
					return 1;
				MultiMap.MultiEntry<K, V> e1 = iter1.next();
				MultiMap.MultiEntry<K, V> e2 = iter2.next();
				int comp = keyType.compare(e1.getKey(), e2.getKey());
				if (comp != 0)
					return comp;
				Iterator<V> valueIter1 = e1.getValues().iterator();
				Iterator<V> valueIter2 = e2.getValues().iterator();
				while (valueIter1.hasNext()) {
					if (!valueIter2.hasNext())
						return 1;
					comp = valueType.compare(valueIter1.next(), valueIter2.next());
					if (comp != 0)
						return comp;
				}
				if (valueIter2.hasNext())
					return -1;
			}
			if (iter1.hasNext())
				return -1;
			return 0;
		}

		@Override
		public boolean isAssignableFrom(FieldType<?> other) {
			if (other instanceof MapType) {
				MapType<?, ?, ?> mt = (MapType<?, ?, ?>) other;
				return keyType.isAssignableFrom(mt.keyType)//
					&& valueType.isAssignableFrom(mt.valueType);
			} else if (other instanceof MultiMapType) {
				MultiMapType<?, ?, ?> mt = (MultiMapType<?, ?, ?>) other;
				return keyType.isAssignableFrom(mt.keyType)//
					&& valueType.isAssignableFrom(mt.valueType);
			} else
				return false;
		}

		@Override
		public M createEmptyStructure() {
			if (isSorted)
				return (M) BetterTreeMultiMap.create(keyType);
			else
				return (M) BetterHashMultiMap.create();
		}

		public M createEmptyMultiMap(Comparator<? super V> valueSort) {
			if (valueSort == null)
				return createEmptyStructure();
			else if (isSorted)
				return (M) BetterTreeMultiMap.<K, V> create(keyType, b -> b.withSortedValues(valueSort, false));
			else
				return (M) BetterHashMultiMap.<K, V> create(b -> b.withSortedValues(valueSort, false));
		}

		@Override
		public M convert(Object value, FieldType<?> otherType) {
			if (equals(otherType))
				return (M) value;

			M newValue = createEmptyStructure();
			if (otherType instanceof MapType) {
				MapType<?, ?, ?> mt = (MapType<?, ?, ?>) otherType;
				for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
					newValue.add(keyType.convert(entry.getKey(), mt.keyType), valueType.convert(entry.getValue(), mt.valueType));
				}
				return newValue;
			} else {
				MultiMapType<?, ?, ?> mt = (MultiMapType<?, ?, ?>) otherType;
				for (MultiMap.MultiEntry<?, ?> entry : ((MultiMap<?, ?>) value).entrySet()) {
					K key = keyType.convert(entry.getKey(), mt.keyType);
					for (Object v : entry.getValues())
						newValue.add(key, valueType.convert(v, mt.valueType));
				}
				return newValue;
			}
		}

		@Override
		public <X extends Throwable> MultiMapType<K, V, M> map(ExFunction<? super FieldType<?>, ? extends FieldType<?>, X> map) throws X {
			FieldType<K> newKT = (FieldType<K>) map.apply(keyType);
			FieldType<V> newVT = (FieldType<V>) map.apply(valueType);
			if (newKT == keyType && newVT == valueType)
				return this;
			else
				return new MultiMapType<>(keyType, valueType, isSorted);
		}

		@Override
		public int hashCode() {
			return Objects.hash(keyType, valueType, isSorted);
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof MultiMapType))
				return false;
			MultiMapType<?, ?, ?> other = (MultiMapType<?, ?, ?>) obj;
			return keyType.equals(other.keyType) && valueType.equals(other.valueType) && isSorted == other.isSorted;
		}

		@Override
		public String toString() {
			StringBuilder str = new StringBuilder();
			if (isSorted)
				str.append("Sorted");
			str.append("MultiMap");
			return str.append('<').append(keyType).append(", ").append(valueType).append('>').toString();
		}
	}

	/**
	 * A simple type that is composed of a constant number of "fields". This type is nice for persisting fields with that are small and
	 * generic, so not worth storing in their own table.
	 */
	public static class TupleType implements ParameterizedType<TupleFieldValue> {
		private final List<FieldType<?>> theComponents;

		public TupleType(FieldType<?>[] components) {
			theComponents = QommonsUtils.unmodifiableCopy(components);
		}

		public int length() {
			return theComponents.size();
		}

		public FieldType<?> getComponent(int index) {
			return theComponents.get(index);
		}

		@Override
		public boolean isInstance(Object value) {
			return value instanceof TupleFieldValue && ((TupleFieldValue) value).length() == length();
		}

		@Override
		public boolean isAssignableFrom(FieldType<?> other) {
			if (other == this)
				return true;
			else if (!(other instanceof TupleType))
				return false;
			TupleType tft = (TupleType) other;
			if (length() != tft.length())
				return false;
			for (int c = 0; c < length(); c++) {
				if (!getComponent(c).isAssignableFrom(tft.getComponent(c)))
					return false;
			}
			return true;
		}

		@Override
		public TupleFieldValue convert(Object value, FieldType<?> valueType) {
			if (value == null)
				return null;
			TupleFieldValue copy = createEmptyStructure();
			for (int c = 0; c < length(); c++) {
				copy.set(c, getComponent(c).convert(((TupleFieldValue) value).get(c), ((TupleType) valueType).getComponent(c)));
			}
			return copy;
		}

		@Override
		public int compare(TupleFieldValue o1, TupleFieldValue o2) {
			for (int c = 0; c < length(); c++) {
				int comp = ((FieldType<Object>) getComponent(c)).compare(o1.get(c), o2.get(c));
				if (comp != 0)
					return comp;
			}
			return 0;
		}

		@Override
		public List<FieldType<?>> getTypeParameters() {
			return theComponents;
		}

		@Override
		public boolean rawTypesEqual(ParameterizedType<?> other) {
			return other instanceof TupleType;
		}

		@Override
		public <X extends Throwable> ParameterizedType<TupleFieldValue> map(ExFunction<? super FieldType<?>, ? extends FieldType<?>, X> map)
			throws X {
			FieldType<?>[] componentCopy = null;
			for (int c = 0; c < length(); c++) {
				FieldType<?> comp = getComponent(c);
				if (comp instanceof ParameterizedType) {
					ParameterizedType<?> mapped = ((ParameterizedType<?>) comp).map(map);
					if (mapped != comp) {
						if (componentCopy == null) {
							componentCopy = new FieldType[length()];
							for (int c2 = 0; c2 < c; c2++)
								componentCopy[c2] = getComponent(c2);
						}
						componentCopy[c] = mapped;
					} else if (componentCopy != null)
						componentCopy[c] = mapped;
				} else if (componentCopy != null)
					componentCopy[c] = comp;
			}
			if (componentCopy != null)
				return new TupleType(componentCopy);
			else
				return this;
		}

		@Override
		public TupleFieldValue createEmptyStructure() {
			return new TupleFieldValue(length());
		}

		@Override
		public int hashCode() {
			return theComponents.size();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			else if (!(obj instanceof TupleType))
				return false;
			return theComponents.equals(((TupleType) obj).theComponents);
		}

		@Override
		public String toString() {
			StringBuilder str = new StringBuilder().append('{');
			boolean first = true;
			for (FieldType<?> component : theComponents) {
				if (first)
					first = false;
				else
					str.append(", ");
				str.append(component);
			}
			return str.append('}').toString();
		}
	}

	public static class BlobType implements FieldType<Blob> {
		private BlobType() {
		}

		@Override
		public boolean isValidKey() {
			return false;
		}

		@Override
		public boolean isSortable() {
			return false;
		}

		@Override
		public int compare(Blob o1, Blob o2) {
			throw new IllegalStateException("Blobs are not sortable");
		}

		@Override
		public boolean isInstance(Object value) {
			return value instanceof Blob;
		}

		@Override
		public boolean isAssignableFrom(FieldType<?> other) {
			return other == this;
		}

		@Override
		public Blob convert(Object value, FieldType<?> valueType) {
			if (valueType == this)
				return (Blob) value;
			else
				throw new IllegalArgumentException("Invalid conversion: " + valueType + " to " + this);
		}

		@Override
		public <FT extends FieldType<?>> FT containsTypeLike(Function<? super FieldType<?>, FT> test) {
			return test.apply(this);
		}

		@Override
		public String toString() {
			return "blob";
		}
	}
}