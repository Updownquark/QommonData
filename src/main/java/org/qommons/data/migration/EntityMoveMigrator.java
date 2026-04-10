package org.qommons.data.migration;

import org.qommons.data.values.GenericEntity;

public interface EntityMoveMigrator extends CustomMigrationComponent {
	Object copyData(GenericEntity oldEntity, GenericEntity newEntity);
}
