package org.qommons.data.migration;

import java.io.IOException;

import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.io.TextParseException;

public interface Migration {
	public static final String MIGRATION_SET_KEY = "Migration Set";

	MigrationSet getMigrationSet();

	LocatedPositionedContent getPosition();

	void apply(MigratableDataSet dataSet, MigrationSession session) throws IOException, TextParseException, DataSetModificationException;
}
