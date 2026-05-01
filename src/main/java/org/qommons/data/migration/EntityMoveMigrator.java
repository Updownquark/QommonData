package org.qommons.data.migration;

import org.qommons.data.values.GenericEntity;

public interface EntityMoveMigrator {
	String getTargetEntity();

	void copyData(GenericEntity oldEntity, GenericEntity newEntity);
}