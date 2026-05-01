package org.qommons.data.migration;

import java.time.Instant;
import java.util.List;

public class MigrationSet extends MigrationSetDef {
	private final List<Migration> theMigrations;

	public MigrationSet(String author, Instant date, String description, List<Migration> migrations) {
		super(author, date, description);
		theMigrations = migrations;
	}

	public List<Migration> getMigrations() {
		return theMigrations;
	}

	public MigrationSetDef toDef() {
		return new MigrationSetDef(author, date, getDescription());
	}
}
