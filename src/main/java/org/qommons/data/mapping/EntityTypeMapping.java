package org.qommons.data.mapping;

import java.util.Map;

import org.qommons.Named;
import org.qommons.collect.DequeList;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;

public class EntityTypeMapping<E> implements Named {
	private final EntityTypeSetMapping theTypeSet;
	private final EntityType theGenericType;
	private final Class<E> theRealType;
	private final DequeList<EntityFieldMapping<?, ?>> theFields;
	private final DequeList<EntityFieldMapping<?, ?>> theIdFields;

	public EntityTypeMapping(EntityTypeSetMapping typeSet, EntityType genericType, Class<E> realType,
		Map<String, EntityFieldMapping<?, ?>> fields) {
		theTypeSet = typeSet;
		theGenericType = genericType;
		theRealType = realType;
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
}
