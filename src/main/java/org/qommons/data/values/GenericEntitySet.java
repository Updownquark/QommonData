package org.qommons.data.values;

import java.io.IOException;

import org.qommons.data.types.EntityTypeSet;
import org.qommons.io.TextParseException;

public interface GenericEntitySet {
	EntityTypeSet getTypes();

	Iterable<GenericEntity> getEntities(String typeName) throws IllegalArgumentException, IOException, TextParseException;

	GenericEntity getEntity(String typeName, Object... id) throws IllegalArgumentException, IOException, TextParseException;

	GenericEntity createEntity(String typeName);

	GenericEntity createEntity(String typeName, Object... ids);
}
