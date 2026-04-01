package org.qommons.data.migration;

import java.util.Map;
import java.util.Set;

import org.qommons.io.FilePosition;

public interface Migration {
	FilePosition getPosition();

	MigrationSet getMigrationSet();

	Set<String> getAffectedEntities();

	Map<String, Set<String>> getRequiredEntitiesAndFields();
}
