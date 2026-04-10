package org.qommons.data.migration;

import org.qommons.Named;
import org.qommons.config.StrictXmlReader;

public class ConfigurableCustomMigrator<M extends CustomMigrationComponent> implements Named {
	private final String theRefId;
	public final Class<? extends M> migrator;
	public final StrictXmlReader configuration;

	public ConfigurableCustomMigrator(String refId, Class<? extends M> migrator, StrictXmlReader configuration) {
		theRefId = refId;
		this.migrator = migrator;
		this.configuration = configuration;
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
