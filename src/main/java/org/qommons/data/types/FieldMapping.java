package org.qommons.data.types;

import java.util.Comparator;
import java.util.Objects;

import org.qommons.data.values.GenericEntity;

public class FieldMapping<F, K, S> {
	public final EntityField<F> parentField;
	public final EntityField<GenericEntity> mappedReferenceField;
	public final EntityField<K> keyField;
	public final EntityField<Integer> indexField;
	public final EntityField<S> sortByField;
	public final Comparator<GenericEntity> entitySort;
	public final boolean parentIsOwner;

	public FieldMapping(EntityField<F> parentField, EntityField<GenericEntity> mappedReferenceField, EntityField<K> keyField,
		EntityField<Integer> indexField, EntityField<S> sortByField, boolean parentIsOwner) {
		this.parentField = parentField;
		this.mappedReferenceField = mappedReferenceField;
		this.keyField = keyField;
		this.indexField = indexField;
		this.sortByField = sortByField;
		entitySort = sortByField == null ? mappedReferenceField.getOwner() : new EntityFieldSort<>(sortByField);
		this.parentIsOwner = parentIsOwner;
	}

	@Override
	public int hashCode() {
		return Objects.hash(parentField, mappedReferenceField, keyField, indexField);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		else if (!(obj instanceof FieldMapping))
			return false;
		FieldMapping<?, ?, ?> other = (FieldMapping<?, ?, ?>) obj;
		return parentField.equals(other.parentField) && mappedReferenceField.equals(other.mappedReferenceField)
			&& Objects.equals(keyField, other.keyField) && Objects.equals(indexField, other.indexField)//
			&& Objects.equals(sortByField, other.sortByField);
	}

	public StringBuilder append(StringBuilder str) {
		str.append(mappedReferenceField.getName());
		if (keyField != null)
			str.append('[').append(keyField.getName()).append(']');
		if (indexField != null)
			str.append('[').append(indexField.getName()).append(']');
		if (sortByField != null)
			str.append('[').append(sortByField.getName()).append(']');
		return str;
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder();
		str.append(parentField.toString()).append(" (by ");
		if (keyField != null)
			str.append(keyField.getName()).append('/');
		str.append(mappedReferenceField.getName()).append(')');
		if (indexField != null)
			str.append('[').append(indexField.getName()).append(']');
		if (sortByField != null)
			str.append('[').append(sortByField.getName()).append(']');
		return str.toString();
	}

	static class EntityFieldSort<S> implements Comparator<GenericEntity> {
		private final EntityField<S> sortByField;

		public EntityFieldSort(EntityField<S> sortByField) {
			this.sortByField = sortByField;
		}

		@Override
		public int compare(GenericEntity o1, GenericEntity o2) {
			if (o1 == null) {
				if (o2 == null)
					return 0;
				else
					return 1;
			} else if (o2 == null)
				return -1;
			else
				return sortByField.getType().compare(o1.get(sortByField), o2.get(sortByField));
		}

		@Override
		public int hashCode() {
			return sortByField.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof EntityFieldSort && sortByField.equals(((EntityFieldSort<?>) obj).sortByField);
		}

		@Override
		public String toString() {
			return "By " + sortByField.getName();
		}
	}
}
