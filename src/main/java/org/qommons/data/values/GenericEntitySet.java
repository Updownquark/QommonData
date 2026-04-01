package org.qommons.data.values;

import org.qommons.collect.BetterSortedSet;
import org.qommons.data.types.EntityTypeSet;

public interface GenericEntitySet {
	EntityTypeSet getTypes();

	BetterSortedSet<GenericEntity> getEntities(String typeName);

	GenericEntity createEntity(String typeName) throws DataSetModificationException;

	GenericEntity createEntity(String typeName, Object... ids) throws DataSetModificationException;

	void deleteEntity(GenericEntity entity) throws DataSetModificationException;
}
