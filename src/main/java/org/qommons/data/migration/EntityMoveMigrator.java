package org.qommons.data.migration;

import org.qommons.data.values.GenericEntity;

public interface EntityMoveMigrator extends CustomMigrationComponent {
	Object getFieldValue(GenericEntity oldEntity, GenericEntity newEntity);
}
