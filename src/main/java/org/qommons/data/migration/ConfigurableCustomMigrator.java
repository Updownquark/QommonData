package org.qommons.data.migration;

import java.util.Map;
import java.util.Set;

import org.qommons.Named;
import org.qommons.config.StrictXmlReader;

public class ConfigurableCustomMigrator<M extends CustomMigrationComponent> implements Named {
	private final String theRefId;
	public final Class<? extends M> migrator;
	public final StrictXmlReader configuration;
	public final Map<String, Set<String>> requiredFields;

	public ConfigurableCustomMigrator(String refId, Class<? extends M> migrator, StrictXmlReader configuration,
		Map<String, Set<String>> requiredFields) {
		theRefId = refId;
		this.migrator = migrator;
		this.configuration = configuration;
		this.requiredFields = requiredFields;
	}

	@Override
	public String getName() {
		return theRefId;
	}

	@Override
	public String toString() {
		return theRefId + "(" + migrator.getName() + ")";
	}
}
