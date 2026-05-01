package org.qommons.data.migration;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.values.GenericEntity;

public interface EntityFieldInitializer {
	void validate(SchemaMigration.AddFieldMigration addField, EntityField<?> field) throws QonfigInterpretationException;

	Object getInitialValue(GenericEntity entity);
}
