package org.qommons.data.migration;

import java.util.Map;

import org.qommons.config.StrictXmlReader;
import org.qommons.data.values.GenericEntitySet;

public interface CustomMigrationComponent {
	void init(MigrationSet migrationSet, StrictXmlReader configuration, Map<String, CustomMigrationComponent> migrators,
		GenericEntitySet entities) throws MigrationException;
}
