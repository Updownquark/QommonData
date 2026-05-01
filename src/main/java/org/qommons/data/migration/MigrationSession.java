package org.qommons.data.migration;

import java.util.HashMap;
import java.util.Map;

import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterSortedSet;

public class MigrationSession {
	private final BetterSortedSet<? extends MigrationSetDef> theAppliedMigrations;
	private final Map<Object, Object> theCustomData;

	public MigrationSession(BetterSortedSet<? extends MigrationSetDef> appliedigrations) {
		theAppliedMigrations = BetterCollections.unmodifiableSortedSet(appliedigrations);
		theCustomData = new HashMap<>();
	}

	public BetterSortedSet<? extends MigrationSetDef> getAppliedMigrations() {
		return theAppliedMigrations;
	}

	public MigrationSession withCustomData(Object key, Object customData) {
		theCustomData.put(key, customData);
		return this;
	}

	public <T> T getCustomData(Object key) {
		return (T) theCustomData.get(key);
	}
}
