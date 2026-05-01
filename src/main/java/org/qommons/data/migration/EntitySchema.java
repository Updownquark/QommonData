package org.qommons.data.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigInterpreterCore;
import org.qommons.data.migration.SchemaMigration.AddEntityMigration;
import org.qommons.data.migration.SchemaMigration.AddEnumMigration;

public class EntitySchema {
	private final List<AddEnumMigration> theEnums;
	private final List<AddEntityMigration> theEntities;

	public EntitySchema(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		List<AddEnumMigration> enums = new ArrayList<>();
		List<AddEntityMigration> entities = new ArrayList<>();
		for (QonfigInterpreterCore.CoreSession migSession : session.forChildren("enums"))
			enums.add(migSession.interpret(AddEnumMigration.class));
		for (QonfigInterpreterCore.CoreSession migSession : session.forChildren("entities"))
			entities.add(migSession.interpret(AddEntityMigration.class));
		theEnums = Collections.unmodifiableList(enums);
		theEntities = Collections.unmodifiableList(entities);
	}

	public List<AddEnumMigration> getEnums() {
		return theEnums;
	}

	public List<AddEntityMigration> getEntities() {
		return theEntities;
	}
}
