package org.qommons.data.migration;

import java.io.IOException;

import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.io.TextParseException;

public interface WholeSetCustomMigrator extends CustomMigrationComponent {
	void migrate(GenericEntitySet entities) throws IOException, TextParseException, MigrationException, DataSetModificationException;
}
