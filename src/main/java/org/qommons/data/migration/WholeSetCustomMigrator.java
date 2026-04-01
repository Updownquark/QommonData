package org.qommons.data.migration;

import org.qommons.data.values.GenericEntitySet;

public interface WholeSetCustomMigrator extends CustomMigrationComponent {
	void migrate(GenericEntitySet entities) throws MigrationException;
}
