package org.qommons.data.mapping;

import java.lang.reflect.Method;

import org.qommons.data.types.EntityField;

public class EntityFieldMapping<G, R> {
	private final EntityField<G> theGenericField;
	private final Method theGetter;

	public EntityFieldMapping(EntityField<G> genericField, Method getter) {
		theGenericField = genericField;
		theGetter = getter;
	}

	public EntityField<G> getGenericField() {
		return theGenericField;
	}

	public Method getGetter() {
		return theGetter;
	}
}
