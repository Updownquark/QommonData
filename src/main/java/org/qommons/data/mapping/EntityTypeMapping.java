package org.qommons.data.mapping;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Consumer;

import org.qommons.Named;
import org.qommons.collect.DequeList;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;

public class EntityTypeMapping<E> implements Named {
	public interface EntitySort<E> extends Comparator<E> {
		int compareId(Object[] id, E entity);
	}

	private final EntityTypeSetMapping theTypeSet;
	private final EntityType theGenericType;
	private final Class<E> theRealType;
	private final DequeList<EntityFieldMapping<?, ?>> theFields;
	private final DequeList<EntityFieldMapping<?, ?>> theIdFields;
	private EntitySort<? super E> theSorting;

	public EntityTypeMapping(EntityTypeSetMapping typeSet, EntityType genericType, Class<E> realType,
		Map<String, EntityFieldMapping<?, ?>> fields, Consumer<? super EntityTypeMapping<E>> configure) {
		theTypeSet = typeSet;
		theGenericType = genericType;
		theRealType = realType;
		configure.accept(this);
		EntityFieldMapping<?, ?>[] allFields = new EntityFieldMapping[genericType.getFields().size()];
		int f = 0;
		for (EntityField<?> field : genericType.getFields())
			allFields[f++] = fields.get(field.getName());
		EntityFieldMapping<?, ?>[] idFields = new EntityFieldMapping[genericType.getIdFields().size()];
		f = 0;
		for (EntityField<?> field : genericType.getIdFields())
			idFields[f++] = allFields[genericType.indexOf(field)];
		theFields = DequeList.of(allFields);
		theIdFields = DequeList.of(idFields);
	}

	void init() {
		for (EntityFieldMapping<?, ?> field : theFields)
			field.init();
		FieldSort<? super E, ?>[] fieldSorting = new FieldSort[theIdFields.size()];
		for (int i = 0; i < theIdFields.size(); i++)
			fieldSorting[i] = createSorting(theIdFields.get(i), theTypeSet);
		theSorting = new EntitySortImpl<>(fieldSorting);
	}

	public EntityTypeSetMapping getTypeSet() {
		return theTypeSet;
	}

	public EntityType getGenericType() {
		return theGenericType;
	}

	public Class<E> getRealType() {
		return theRealType;
	}

	@Override
	public String getName() {
		return theGenericType.getName();
	}

	public EntitySort<? super E> getSorting() {
		return theSorting;
	}

	public DequeList<EntityFieldMapping<?, ?>> getIdFields() {
		return theIdFields;
	}

	public DequeList<EntityFieldMapping<?, ?>> getFields() {
		return theFields;
	}

	public EntityFieldMapping<?, ?> getField(String name) {
		EntityField<?> field = theGenericType.getField(name);
		if (field == null)
			return null;
		return theFields.get(theGenericType.indexOf(field));
	}

	public EntityFieldMapping<?, ?> getField(int fieldIndex) {
		return theFields.get(fieldIndex);
	}

	public Object[] getId(E entity) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Object[] id = new Object[theIdFields.size()];
		for (int i = 0; i < id.length; i++)
			id[i] = theIdFields.get(i).getGetter().invoke(entity);
		return id;
	}

	@Override
	public String toString() {
		return theGenericType.getName();
	}

	private static <E, F> FieldSort<? super E, ?> createSorting(EntityFieldMapping<?, F> type, EntityTypeSetMapping types) {
		Comparator<?> fieldSort = type.getSorting();
		return new FieldSort<>(type.getGetter(), (Comparator<? super F>) fieldSort);
	}

	static class FieldSort<E, F> implements Comparator<E> {
		private final Method theGetter;
		private final Comparator<? super F> theFieldSort;

		FieldSort(Method getter, Comparator<? super F> fieldSort) {
			theGetter = getter;
			theFieldSort = fieldSort;
		}

		public F get(E entity) {
			try {
				return (F) theGetter.invoke(entity);
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new IllegalStateException("Could not retrieve field value", e);
			}
		}

		public int compareValue(F value, E entity) {
			F entityValue = get(entity);
			return theFieldSort.compare(value, entityValue);
		}

		@Override
		public int compare(E o1, E o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			F f1 = get(o1);
			F f2 = get(o2);
			return theFieldSort.compare(f1, f2);
		}
	}

	static class EntitySortImpl<E> implements EntitySort<E> {
		private final FieldSort<? super E, ?>[] theComponents;

		EntitySortImpl(FieldSort<? super E, ?>[] components) {
			this.theComponents = components;
		}

		@Override
		public int compare(E o1, E o2) {
			int comp = 0;
			for (int c = 0; comp == 0 && c < theComponents.length; c++)
				comp = theComponents[c].compare(o1, o2);
			return comp;
		}

		@Override
		public int compareId(Object[] id, E entity) {
			if (id.length != theComponents.length)
				throw new IllegalArgumentException("Expected " + theComponents.length + " ID values, but received " + id.length);
			int comp = 0;
			for (int c = 0; comp == 0 && c < theComponents.length; c++)
				comp = compareField(theComponents[c], id[c], entity);
			return comp;
		}

		private static <E, F> int compareField(FieldSort<? super E, F> sort, Object id, E entity) {
			return sort.compareValue((F) id, entity);
		}
	}
}
