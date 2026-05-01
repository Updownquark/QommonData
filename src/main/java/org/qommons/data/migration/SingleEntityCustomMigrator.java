package org.qommons.data.migration;

import java.io.IOException;

import org.qommons.config.QonfigInterpreterCore;
import org.qommons.data.types.EntityType;
import org.qommons.data.values.GenericEntity;

public interface SingleEntityCustomMigrator {
	public static final String AFFECTED_ENTITY = "ForEach Affected Entity";

	public static EntityType getAffectedEntity(QonfigInterpreterCore.CoreSession session) {
		return session.get(AFFECTED_ENTITY, EntityType.class);
	}

	void prepare(EntityType entity);

	void handle(GenericEntity entity) throws IOException;
}
