package org.qommons.data.types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.qommons.StringUtils;
import org.qommons.collect.BetterHashMultiMap;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSortedList;
import org.qommons.collect.BetterSortedMultiMap;
import org.qommons.collect.DequeList;
import org.qommons.collect.MultiEntryHandle;
import org.qommons.data.migration.MigrationException;
import org.qommons.io.FilePosition;
import org.qommons.tree.BetterTreeMultiMap;
import org.qommons.tree.SortedTreeList;

public interface FieldType<F> extends Comparator<F> {
	/**
	 * A placeholder to indicate that an ID field for a newly created entity type should be a reference to another instance of the same type
	 */
	public static final SelfReferenceType SELF = SelfReferenceType.INSTANCE;

	boolean isInstance(Object value);

	boolean isAssignableFrom(FieldType<?> other);

	F convert(Object value, FieldType<?> valueType);

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

		public static final SimpleType<Boolean> BOOLEAN = add(new SimpleType<>(Boolean.class), boolean.class);
		public static final SimpleType<Character> CHAR = add(new SimpleType<>(Character.class), char.class);
		public static final SimpleType<Byte> BYTE = add(new SimpleType<>(Byte.class), byte.class);
		public static final SimpleType<Short> SHORT = add(new SimpleType<>(Short.class), short.class);
		public static final SimpleType<Integer> INT = add(new SimpleType<>(Integer.class), int.class);
		public static final SimpleType<Long> LONG = add(new SimpleType<>(Long.class), long.class);
		public static final SimpleType<Float> FLOAT = add(new SimpleType<>(Float.class), float.class);
		public static final SimpleType<Double> DOUBLE = add(new SimpleType<>(Double.class), double.class);
		public static final SimpleType<String> STRING = add(new SimpleType<>(String.class), null);

		public final Class<F> type;

		private SimpleType(Class<F> type) {
			this.type = type;
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

		public F parse(String text, Supplier<FilePosition> source) throws MigrationException {
			if (this == BOOLEAN) {
				switch (text) {
				case "true":
				case "TRUE":
					return (F) Boolean.TRUE;
				case "false":
				case "FALSE":
					return (F) Boolean.FALSE;
				default:
					throw new MigrationException("Expected 'true' or 'false', not '" + text + "'", source.get());
				}
			} else if (this == CHAR) {
				if (text.length() != 1)
					throw new MigrationException("Expected a single character", source.get());
				return (F) Character.valueOf(text.charAt(0));
			} else if (this == STRING) {
				return (F) text;
			} else if (this == BYTE) {
				try {
					return (F) Byte.valueOf(text);
				} catch (NumberFormatException e) {
					throw new MigrationException("Could not parse byte from '" + text + "'", source.get(), e);
				}
			} else if (this == SHORT) {
				try {
					return (F) Short.valueOf(text);
				} catch (NumberFormatException e) {
					throw new MigrationException("Could not parse short from '" + text + "'", source.get(), e);
				}
			} else if (this == INT) {
				try {
					return (F) Integer.valueOf(text);
				} catch (NumberFormatException e) {
					throw new MigrationException("Could not parse int from '" + text + "'", source.get(), e);
				}
			} else if (this == LONG) {
				try {
					return (F) Long.valueOf(text);
				} catch (NumberFormatException e) {
					throw new MigrationException("Could not parse long from '" + text + "'", source.get(), e);
				}
			} else if (this == FLOAT) {
				try {
					return (F) Float.valueOf(text);
				} catch (NumberFormatException e) {
					throw new MigrationException("Could not parse float from '" + text + "'", source.get(), e);
				}
			} else if (this == DOUBLE) {
				try {
					return (F) Double.valueOf(text);
				} catch (NumberFormatException e) {
					throw new MigrationException("Could not parse double from '" + text + "'", source.get(), e);
				}
			} else {
				throw new IllegalStateException("Who even am I?");
			}
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
			return type.toString();
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
	}

	public static class CollectionType<E, C extends Collection<E>> implements ParameterizedType<C> {
		public final FieldType<E> componentType;
		public final boolean isSorted;
		public final boolean isDistinct;

		public CollectionType(FieldType<E> componentType, boolean isSorted, boolean isDistinct) {
			this.componentType = componentType;
			this.isSorted = isSorted;
			this.isDistinct = isDistinct;
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
					if (!(value instanceof SortedSet))
						return false;
				} else if (!(value instanceof BetterSortedList))
					return false;
			} else if (isDistinct) {
				if (!(value instanceof Set))
					return false;
			} else if (!(value instanceof List))
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

		public C createEmptyCollection() {
			if (isSorted) {
				if (isDistinct)
					return (C) new TreeSet<>(componentType);
				else
					return (C) SortedTreeList.createTreeList(componentType);
			} else if (isDistinct)
				return (C) new LinkedHashSet<>();
			else
				return (C) new ArrayList<>();
		}

		@Override
		public C convert(Object value, FieldType<?> valueType) {
			CollectionType<?, ?> ct = (CollectionType<?, ?>) valueType;
			if (isSorted == ct.isSorted && isDistinct == ct.isDistinct && componentType.equals(ct.componentType))
				return (C) value;
			C newValue = createEmptyCollection();
			if (isSorted) {
				if (isDistinct)
					newValue = (C) new TreeSet<>(componentType);
				else
					newValue = (C) SortedTreeList.createTreeList(componentType);
			} else if (isDistinct)
				newValue = (C) new LinkedHashSet<>();
			else
				newValue = (C) new ArrayList<>();
			for (Object v : (Collection<?>) value)
				newValue.add(componentType.convert(v, ct.componentType));
			return null;
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

	public static class MapType<K, V, M extends Map<K, V>> implements ParameterizedType<M> {
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
				if (!(value instanceof SortedMap))
					return false;
			} else if (!(value instanceof Map))
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

		public M createEmptyMap() {
			if (isSorted)
				return (M) new TreeMap<>(keyType);
			else
				return (M) new LinkedHashMap<>();
		}

		@Override
		public M convert(Object value, FieldType<?> otherType) {
			if (equals(otherType))
				return (M) value;
			MapType<?, ?, ?> mt = (MapType<?, ?, ?>) otherType;
			M newValue = createEmptyMap();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				newValue.put(keyType.convert(entry.getKey(), mt.keyType), valueType.convert(entry.getValue(), mt.valueType));
			}
			return newValue;
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
			Iterator<? extends MultiEntryHandle<K, V>> iter1 = o1.entrySet().iterator();
			Iterator<? extends MultiEntryHandle<K, V>> iter2 = o2.entrySet().iterator();
			while (iter1.hasNext()) {
				if (!iter2.hasNext())
					return 1;
				MultiEntryHandle<K, V> e1 = iter1.next();
				MultiEntryHandle<K, V> e2 = iter2.next();
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

		public M createEmptyMap() {
			if (isSorted)
				return (M) BetterTreeMultiMap.create(keyType);
			else
				return (M) BetterHashMultiMap.create();
		}

		@Override
		public M convert(Object value, FieldType<?> otherType) {
			if (equals(otherType))
				return (M) value;

			M newValue = createEmptyMap();
			if (otherType instanceof MapType) {
				MapType<?, ?, ?> mt = (MapType<?, ?, ?>) otherType;
				for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
					newValue.add(keyType.convert(entry.getKey(), mt.keyType), valueType.convert(entry.getValue(), mt.valueType));
				}
				return newValue;
			} else {
				MultiMapType<?, ?, ?> mt = (MultiMapType<?, ?, ?>) otherType;
				for (MultiEntryHandle<?, ?> entry : ((BetterMultiMap<?, ?>) value).entrySet()) {
					K key = keyType.convert(entry.getKey(), mt.keyType);
					for (Object v : entry.getValues())
						newValue.add(key, valueType.convert(v, mt.valueType));
				}
				return newValue;
			}
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
}
