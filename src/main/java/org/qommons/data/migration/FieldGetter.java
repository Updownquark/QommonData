package org.qommons.data.migration;

import java.util.function.Function;

import org.qommons.BiTuple;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;

public interface FieldGetter<T> extends Function<GenericEntity, T>, FieldResolving<T> {
	public class Simple<T> extends FieldResolving.Abstract<T> implements FieldGetter<T> {
		Simple(FieldPath<T> path) {
			super(path);
		}

		@Override
		public T apply(GenericEntity entity) {
			GenericEntity target = getTargetEntity(entity);
			return target == null ? null : target.get(getTargetField());
		}
	}

	static <T> BiTuple<FieldType<T>, FieldGetter<T>> parse(EntityType entityType, LocatedPositionedContent from)
		throws QonfigInterpretationException {
		FieldPath<T> path = FieldPath.parse(entityType, from);
		return new BiTuple<>(path.lastField.getType(), new Simple<>(path));
	}
}