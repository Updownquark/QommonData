package org.qommons.data.migration;

import java.util.Map;

import org.qommons.config.StrictXmlReader;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.io.FilePosition;

public interface CustomMigrationComponent {
	void init(MigrationSet migrationSet, StrictXmlReader configuration, Map<String, CustomMigrationComponent> migrators)
		throws MigrationException;

	void validate(EntityTypeSet types, FilePosition source) throws MigrationException;
}
