package org.qommons.data.migration;

import java.io.IOException;

import org.qommons.BiTuple;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigInterpreterCore;
import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;

public class SetMigrator implements SingleEntityCustomMigrator {
	private final LocatedPositionedContent fieldName;
	private final LocatedPositionedContent fieldText;
	private final FieldSetter<Object, Object> theSetter;
	private Object theValue;

	public SetMigrator(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		fieldName = session.attributes().get("field").getLocatedContent();
		fieldText = session.attributes().get("value").getLocatedContent();
		EntityType entity = session.get(AFFECTED_ENTITY, EntityType.class);
		BiTuple<FieldType<Object>, FieldGetter<Object>> getter = FieldGetter.parse(entity, fieldName);
		theSetter = FieldSetter.parse(entity, getter.getValue1(), fieldName, fieldName);
	}

	@Override
	public void prepare(MigratableDataSet dataSet, EntityType entity, MigrationSession session) throws QonfigInterpretationException {
		theSetter.prepare(entity);
		theValue = MigrationUtil.parseFieldValue(fieldText, theSetter.getTargetField().getType(), dataSet, fieldText::getPosition);
	}

	@Override
	public void handle(GenericEntity entity) throws IOException {
		theSetter.accept(entity, theValue);
	}
}
