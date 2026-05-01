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

public class CopyMigrator implements SingleEntityCustomMigrator {
	public final LocatedPositionedContent from;
	public final LocatedPositionedContent to;
	private final FieldGetter<Object> theFromGetter;
	private final FieldSetter<Object, Object> theToSetter;

	public CopyMigrator(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		from = session.attributes().get("from").getLocatedContent();
		to = session.attributes().get("to").getLocatedContent();
		EntityType entity = session.get(AFFECTED_ENTITY, EntityType.class);
		BiTuple<? extends FieldType<?>, ? extends FieldGetter<?>> fg = FieldGetter.parse(entity, from);
		theFromGetter = (FieldGetter<Object>) fg.getValue2();
		theToSetter = FieldSetter.parse(entity, (FieldType<Object>) fg.getValue1(), from, to);
	}

	@Override
	public void prepare(MigratableDataSet dataSet, EntityType entity, MigrationSession session) throws QonfigInterpretationException {
		theFromGetter.prepare(entity);
		theToSetter.prepare(entity);
	}

	@Override
	public void handle(GenericEntity entity) throws IOException {
		Object fromValue = theFromGetter.apply(entity);
		theToSetter.accept(entity, fromValue);
	}
}
