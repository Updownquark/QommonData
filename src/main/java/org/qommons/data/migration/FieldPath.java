package org.qommons.data.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.io.PositionedContent;

public class FieldPath<T> {
	final List<EntityField<GenericEntity>> prePath;
	final EntityField<T> lastField;

	public FieldPath(List<EntityField<GenericEntity>> prePath, EntityField<T> lastField) {
		this.prePath = prePath == null ? Collections.emptyList() : prePath;
		this.lastField = lastField;
	}

	String[] getPrePath() {
		String[] preFieldNames = new String[prePath.size()];
		for (int i = 0; i < preFieldNames.length; i++)
			preFieldNames[0] = prePath.get(i).getName();
		return preFieldNames;
	}

	public static <T> FieldPath<T> parse(EntityType entityType, LocatedPositionedContent path)
		throws QonfigInterpretationException {
		List<EntityField<GenericEntity>> prePath = new ArrayList<>();
		EntityField<?>[] lastField = new EntityField[1];
		PositionedContent.split(path, '.', fieldName -> {
			if (lastField[0] == null) {
				lastField[0] = entityType.getField(fieldName.toString());
				if (lastField[0] == null)
					throw new QonfigInterpretationException("No such field " + entityType + "." + fieldName, fieldName);
			} else if (lastField[0].getType() instanceof EntityType) {
				EntityType lastType = (EntityType) lastField[0].getType();
				prePath.add((EntityField<GenericEntity>) lastField[0]);
				lastField[0] = lastType.getField(fieldName.toString());
				if (lastField[0] == null)
					throw new QonfigInterpretationException("No such field " + lastType + "." + fieldName, fieldName);
			} else
				throw new QonfigInterpretationException(entityType + "." + path.subSequence(0, fieldName.getPosition(0).getPosition() - 1)
				+ " is type " + lastField[0].getType() + ", not an entity. '" + fieldName + "' cannot be resolved", fieldName);
		});
		if (lastField[0] == null)
			throw new QonfigInterpretationException("Empty field name", path);
		return new FieldPath<>(prePath, (EntityField<T>) lastField[0]);
	}
}