package org.qommons.data.values;

import java.io.IOException;
import java.util.function.Predicate;

import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.io.BetterFile;
import org.qommons.io.TextParseException;

public interface EntitySetPersistence {
	boolean mayBePersistedData(BetterFile file, EntityTypeSet typeSet) throws IOException, TextParseException;

	void populate(GenericEntitySet entitySet, BetterFile directory) throws IOException, TextParseException;

	void persistEntity(EntityType entityType, Iterable<? extends GenericEntity> entities, Predicate<? super GenericEntity> changedTest,
		BetterFile destDataDir) throws IOException;

	void persist(GenericEntitySet dataSet, BetterFile destDataDir) throws IOException;

	String getPersistentEntityHash(BetterFile dataDir, EntityType type) throws IOException;

	void deleteExclusiveEntityContent(BetterFile dataDir, EntityType type) throws IOException;
}
