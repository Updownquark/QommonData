package org.qommons.data.migration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.qommons.StringUtils;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.values.GenericEntity;

public interface FieldResolving<T> {
	boolean hasPath();

	String getTargetFieldName();

	void prepare(EntityType entity) throws QonfigInterpretationException;

	EntityField<T> getTargetField();

	public class Abstract<T> implements FieldResolving<T> {
		private final String[] thePrePath;
		private final String theLastFieldName;
		final List<EntityField<GenericEntity>> theResolvedPath;
		EntityField<T> theTargetField;

		Abstract(FieldPath<T> path) {
			this.thePrePath = path.getPrePath();
			this.theLastFieldName = path.lastField.getName();
			theResolvedPath = new ArrayList<>(thePrePath.length);
		}

		@Override
		public boolean hasPath() {
			return thePrePath.length > 0;
		}

		@Override
		public String getTargetFieldName() {
			return theLastFieldName;
		}

		@Override
		public void prepare(EntityType entity) {
			theResolvedPath.clear();
			EntityType lastType = entity;
			for (String preFieldName : thePrePath) {
				EntityField<?> field = lastType.getField(preFieldName);
				theResolvedPath.add((EntityField<GenericEntity>) field);
				lastType = (EntityType) field.getType();
			}
			theTargetField = (EntityField<T>) lastType.getField(theLastFieldName);
		}

		@Override
		public EntityField<T> getTargetField() {
			return theTargetField;
		}

		public void setTargetField(EntityField<T> targetField) {
			this.theTargetField = targetField;
		}

		protected GenericEntity getTargetEntity(GenericEntity sourceEntity) {
			GenericEntity e = sourceEntity;
			for (EntityField<GenericEntity> field : theResolvedPath) {
				e = e.get(field);
				if (e == null)
					return null;
			}
			return e;
		}

		@Override
		public String toString() {
			if (thePrePath.length == 0)
				return theLastFieldName;
			StringBuilder str = new StringBuilder();
			StringUtils.print(str, ".", Arrays.asList(thePrePath), StringBuilder::append);
			str.append('.').append(theLastFieldName);
			return str.toString();
		}
	}
}