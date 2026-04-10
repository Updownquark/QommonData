package org.qommons.data.migration;

import java.io.IOException;
import java.util.Map;

import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.io.FilePosition;
import org.qommons.io.TextParseException;

public interface Migration {
	FilePosition getPosition();

	MigrationSet getMigrationSet();

	void validate(ModifiableEntityTypeSet entities, Map<String, CustomMigrationComponent> migrators) throws MigrationException;

	void apply(MigratableDataSet dataSet, Map<String, CustomMigrationComponent> migrators)
		throws IOException, TextParseException, MigrationException, DataSetModificationException;
}
