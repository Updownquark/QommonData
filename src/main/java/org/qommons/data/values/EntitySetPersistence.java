package org.qommons.data.values;

import java.io.IOException;
import java.util.function.Predicate;

import org.qommons.data.types.EntityType;
import org.qommons.io.BetterFile;
import org.qommons.io.TextParseException;

public interface EntitySetPersistence {
	boolean mayBePersistedData(BetterFile file) throws IOException, TextParseException;

	void populate(GenericEntitySet entitySet, BetterFile directory) throws IOException, TextParseException;

	void persist(GenericEntitySet dataSet, BetterFile destDataDir, Predicate<? super EntityType> excludeEntities)
		throws IOException, TextParseException;

	String getPersistentEntityHash(BetterFile dataDir, EntityType type) throws IOException;

	void deleteExclusiveEntityContent(BetterFile dataDir, EntityType type) throws IOException;
}
