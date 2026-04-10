package org.qommons.data.migration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class MigrationSet extends MigrationSetDef {
	private final Map<String, ConfigurableCustomMigrator<?>> theMigrators;
	private final List<Migration> theMigrations;

	public MigrationSet(String author, Instant date, String description, Map<String, ConfigurableCustomMigrator<?>> migrators,
		List<Migration> migrations) {
		super(author, date, description);
		theMigrators = migrators;
		theMigrations = migrations;
	}

	public Map<String, ConfigurableCustomMigrator<?>> getMigrators() {
		return theMigrators;
	}

	public List<Migration> getMigrations() {
		return theMigrations;
	}

	public MigrationSetDef toDef() {
		return new MigrationSetDef(author, date, getDescription());
	}
}
