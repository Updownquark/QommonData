package org.qommons.data.migration;

import java.io.IOException;

import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;

public interface SingleEntityCustomMigrator extends CustomMigrationComponent {
	void handle(GenericEntity entity, GenericEntitySet entitySet) throws IOException, MigrationException, DataSetModificationException;
}
