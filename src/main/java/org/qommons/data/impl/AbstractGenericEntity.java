package org.qommons.data.impl;

import java.util.Arrays;
import java.util.Objects;

import org.qommons.ArrayUtils;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;

public abstract class AbstractGenericEntity implements GenericEntity {
	private final EntityType theType;
	private final GenericEntitySet theEntitySet;
	private Object[] theFieldValues;

	protected AbstractGenericEntity(EntityType type, GenericEntitySet entitySet, Object[] fieldValues) {
		theType = type;
		theEntitySet = entitySet;
		theFieldValues = fieldValues;
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
	public GenericEntity set(EntityField<?> field, Object value) {
		int fieldIndex = theType.indexOf(field);
		if (fieldIndex < 0)
			throw new IllegalArgumentException("Field " + field + " does not belong to entity type " + theType);
		else if (!field.getType().isInstance(value)) {
			throw new IllegalArgumentException("Value " + value + (value == null ? "" : " (type " + value.getClass().getName() + ")")
				+ " is not valid for field " + field);
		}
		theFieldValues[fieldIndex] = value;
		return this;
	}

	@Override
	public GenericEntity immutableCopy() {
		return new SimpleImmutableEntity(theType, theEntitySet, theFieldValues.clone());
	}

	protected void fieldAdded(int index, Object value) {
		theFieldValues = ArrayUtils.add(theFieldValues, value, index);
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
}
