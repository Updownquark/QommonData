package org.qommons.data.migration;

import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;

public interface SingleEntityCustomMigrator extends CustomMigrationComponent {
	void handle(GenericEntity entity, GenericEntitySet entitySet) throws MigrationException;
}
