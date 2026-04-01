package org.qommons.data.migration;

import org.qommons.data.values.GenericEntity;

public interface EntityFieldInitializer extends CustomMigrationComponent {
	Object getInitialValue(GenericEntity entity) throws MigrationException;
}
