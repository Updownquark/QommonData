package org.qommons.data.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigInterpreterCore;
import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.types.EntityType;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.io.TextParseException;

public class ForEachMigration implements Migration {
	private final MigrationSet theMigrationSet;
	private final LocatedPositionedContent thePosition;
	public final LocatedPositionedContent entityName;
	private final List<SingleEntityCustomMigrator> actions;

	public ForEachMigration(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		thePosition = session.getElement().getFilePosition();
		theMigrationSet = (MigrationSet) session.get(MIGRATION_SET_KEY);
		entityName = session.attributes().get("entity").getLocatedContent();
		List<SingleEntityCustomMigrator> migs = new ArrayList<>();
		EntityType entity = SchemaHistory.get(session).getTypeSet().getEntityType(entityName.toString());
		if (entity == null)
			throw new QonfigInterpretationException("No such entity type '" + entityName + "'", entityName);
		session.put(SingleEntityCustomMigrator.AFFECTED_ENTITY, entity);
		for (QonfigInterpreterCore.CoreSession migSession : session.forChildren("actions")) {
			migs.add(migSession.interpret(SingleEntityCustomMigrator.class));
		}
		actions = Collections.unmodifiableList(migs);
	}

	@Override
	public MigrationSet getMigrationSet() {
		return theMigrationSet;
	}

	@Override
	public LocatedPositionedContent getPosition() {
		return getPosition();
	}

	@Override
	public void apply(MigratableDataSet dataSet, MigrationSession session)
		throws IOException, TextParseException, DataSetModificationException {
		EntityType entityType = dataSet.getTypes().getEntityType(entityName.toString());
		if (entityType == null)
			throw new DataSetModificationException("No such entity type '" + entityName + "'");
		for (SingleEntityCustomMigrator action : actions) {
			action.prepare(dataSet, entityType, session);
		}
		for (GenericEntity entity : dataSet.getEntities(entityType.getName())) {
			for (SingleEntityCustomMigrator action : actions) {
				action.handle(entity);
				if (entity.isDeleted())
					break;
			}
		}
	}
}
