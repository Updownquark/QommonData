package org.qommons.data.migration;

import java.util.Collection;
import java.util.function.BiConsumer;

import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;

public interface FieldSetter<F, T> extends BiConsumer<GenericEntity, F>, FieldResolving<T> {
	public abstract class Abstract<F, T> extends FieldResolving.Abstract<T> implements FieldSetter<F, T> {
		Abstract(FieldPath<T> path) {
			super(path);
		}

		@Override
		public abstract void accept(GenericEntity entity, F fromValue);
	}

	static <F, T> FieldSetter<F, T> parse(EntityType entity, FieldType<F> fromType, LocatedPositionedContent from,
		LocatedPositionedContent to) throws QonfigInterpretationException {
		FieldPath<T> toPath = FieldPath.parse(entity, to);
		FieldType<?> type = toPath.lastField.getType();
		if (type instanceof FieldType.SimpleType || type instanceof EntityType || type instanceof EnumType) {
			if (!type.isAssignableFrom(fromType))
				throw new QonfigInterpretationException(
					"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to,
					to);
			return new Abstract<F, T>(toPath) {
				@Override
				public void accept(GenericEntity entity2, F fromValue) {
					GenericEntity target = getTargetEntity(entity2);
					if (target != null)
						target.set(getTargetField(), fromValue);
				}
			};
		} else if (type instanceof FieldType.CollectionType) {
			FieldType.CollectionType<?, ?> collType = (FieldType.CollectionType<?, ?>) type;
			if (collType.isComplex())
				throw new QonfigInterpretationException("Complex types are not supported here: " + collType, to);
			if (fromType instanceof FieldType.CollectionType) {
				if (!collType.componentType.isAssignableFrom(((FieldType.CollectionType<?, ?>) fromType).componentType))
					throw new QonfigInterpretationException("Type " + fromType + " of " + entity + "." + from + " cannot be copied to type "
						+ type + " of " + entity + "." + to, to);
				return new Abstract<F, T>(toPath) {
					@Override
					public void accept(GenericEntity entity2, F fromValue) {
						GenericEntity target = getTargetEntity(entity2);
						Collection<Object> destCollection = (Collection<Object>) target.get(getTargetField());
						destCollection.addAll((Collection<?>) fromValue);
					}
				};
			} else {
				if (!collType.componentType.isAssignableFrom(fromType))
					throw new QonfigInterpretationException("Type " + fromType + " of " + entity + "." + from + " cannot be copied to type "
						+ type + " of " + entity + "." + to, to);
				return new Abstract<F, T>(toPath) {
					@Override
					public void accept(GenericEntity entity2, F fromValue) {
						GenericEntity target = getTargetEntity(entity2);
						if (target == null)
							return;
						Collection<F> destCollection = (Collection<F>) target.get(getTargetField());
						destCollection.add(fromValue);
					}
				};
			}
		} else
			throw new QonfigInterpretationException(
				"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to, to);
	}
}