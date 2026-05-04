package org.qommons.data.migration;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityType;
import org.qommons.data.values.GenericEntity;

public interface EntityMoveMigrator {
	public static final String SOURCE_ENTITY = "Entity Move Source";
	public static final String TARGET_ENTITY = "Entity Move Target";

	String getTargetEntity();

	void prepare(EntityType sourceType, EntityType targetType) throws QonfigInterpretationException;

	GenericEntity getOrCreateReplacement(GenericEntity sourceEntity, EntityType targetType) throws QonfigInterpretationException;

	void copyData(GenericEntity oldEntity, GenericEntity newEntity);
}