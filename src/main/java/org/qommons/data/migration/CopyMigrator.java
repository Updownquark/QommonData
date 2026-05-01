package org.qommons.data.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.qommons.BiTuple;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigInterpreterCore;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.io.PositionedContent;

public class CopyMigrator implements SingleEntityCustomMigrator {
	public final LocatedPositionedContent from;
	public final LocatedPositionedContent to;
	private final FieldGetter<Object> theFromGetter;
	private final FieldSetter<Object> theToSetter;

	public CopyMigrator(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		from = session.attributes().get("from").getLocatedContent();
		to = session.attributes().get("to").getLocatedContent();
		EntityType entity = session.get(AFFECTED_ENTITY, EntityType.class);
		BiTuple<? extends FieldType<?>, ? extends FieldGetter<?>> fg = getFromGetter(entity, from);
		theFromGetter = (FieldGetter<Object>) fg.getValue2();
		theToSetter = getToSetter(entity, (FieldType<Object>) fg.getValue1(), from, to);
	}

	@Override
	public void prepare(EntityType entity) {
		theFromGetter.prepare(entity);
		theToSetter.prepare(entity);
	}

	@Override
	public void handle(GenericEntity entity) throws IOException {
		Object fromValue = theFromGetter.apply(entity);
		theToSetter.accept(entity, fromValue);
	}

	private static class FieldPath<T> {
		final List<EntityField<GenericEntity>> prePath;
		final EntityField<T> lastField;

		FieldPath(List<EntityField<GenericEntity>> prePath, EntityField<T> lastField) {
			this.prePath = prePath;
			this.lastField = lastField;
		}

		String[] getPrePath() {
			String[] preFieldNames = new String[prePath.size()];
			for (int i = 0; i < preFieldNames.length; i++)
				preFieldNames[0] = prePath.get(i).getName();
			return preFieldNames;
		}
	}

	private static <T> FieldPath<T> parsePath(EntityType entityType, LocatedPositionedContent path)
		throws QonfigInterpretationException {
		List<EntityField<GenericEntity>> prePath = new ArrayList<>();
		EntityField<?>[] lastField = new EntityField[1];
		PositionedContent.split(path, '.', fieldName -> {
			if (lastField[0] == null) {
				lastField[0] = entityType.getField(fieldName.toString());
				if (lastField[0] == null)
					throw new QonfigInterpretationException("No such field " + entityType + "." + fieldName, fieldName);
			} else if (lastField[0].getType() instanceof EntityType) {
				EntityType lastType = (EntityType) lastField[0].getType();
				prePath.add((EntityField<GenericEntity>) lastField[0]);
				lastField[0] = lastType.getField(fieldName.toString());
				if (lastField[0] == null)
					throw new QonfigInterpretationException("No such field " + lastType + "." + fieldName, fieldName);
			} else
				throw new QonfigInterpretationException(entityType + "." + path.subSequence(0, fieldName.getPosition(0).getPosition() - 1)
				+ " is type " + lastField[0].getType() + ", not an entity. '" + fieldName + "' cannot be resolved", fieldName);
		});
		if (lastField[0] == null)
			throw new QonfigInterpretationException("Empty field name", path);
		return new FieldPath<>(prePath, (EntityField<T>) lastField[0]);
	}

	private static <T> BiTuple<FieldType<T>, FieldGetter<T>> getFromGetter(EntityType entityType, LocatedPositionedContent from)
		throws QonfigInterpretationException {
		FieldPath<T> path = parsePath(entityType, from);
		return new BiTuple<>(path.lastField.getType(), new FieldGetterImpl<>(path));
	}

	private static <F, T> FieldSetter<F> getToSetter(EntityType entity, FieldType<F> fromType, LocatedPositionedContent from,
		LocatedPositionedContent to) throws QonfigInterpretationException {
		FieldPath<T> toPath = parsePath(entity, to);
		FieldType<?> type = toPath.lastField.getType();
		if (type instanceof FieldType.SimpleType || type instanceof EntityType || type instanceof EnumType) {
			if (!type.isAssignableFrom(fromType))
				throw new QonfigInterpretationException(
					"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to,
					to);
			return new AbstractFieldSetter<F, F>((FieldPath<F>) toPath) {
				@Override
				public void accept(GenericEntity entity2, F fromValue) {
					GenericEntity target = getTargetEntity(entity2);
					if (target != null)
						target.set(targetField, fromValue);
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
				return new AbstractFieldSetter<F, T>(toPath) {
					@Override
					public void accept(GenericEntity entity2, F fromValue) {
						GenericEntity target = getTargetEntity(entity2);
						Collection<Object> destCollection = (Collection<Object>) target.get(targetField);
						destCollection.addAll((Collection<?>) fromValue);
					}
				};
			} else {
				if (!collType.componentType.isAssignableFrom(fromType))
					throw new QonfigInterpretationException("Type " + fromType + " of " + entity + "." + from + " cannot be copied to type "
						+ type + " of " + entity + "." + to, to);
				return new AbstractFieldSetter<F, T>(toPath) {
					@Override
					public void accept(GenericEntity entity2, F fromValue) {
						GenericEntity target = getTargetEntity(entity2);
						if (target == null)
							return;
						Collection<F> destCollection = (Collection<F>) target.get(targetField);
						destCollection.add(fromValue);
					}
				};
			}
		} else
			throw new QonfigInterpretationException(
				"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to, to);
	}

	interface FieldResolving {
		void prepare(EntityType entity);
	}

	interface FieldGetter<T> extends Function<GenericEntity, T>, FieldResolving {
	}

	interface FieldSetter<T> extends BiConsumer<GenericEntity, T>, FieldResolving {
	}

	static abstract class AbstractFieldResolving<T> implements FieldResolving {
		private final String[] prePath;
		private final String lastFieldName;
		final List<EntityField<GenericEntity>> resolvedPath;
		EntityField<T> targetField;

		AbstractFieldResolving(FieldPath<T> path) {
			this.prePath = path.getPrePath();
			this.lastFieldName = path.lastField.getName();
			resolvedPath = new ArrayList<>(prePath.length);
		}

		@Override
		public void prepare(EntityType entity) {
			resolvedPath.clear();
			EntityType lastType = entity;
			for (String preFieldName : prePath) {
				EntityField<?> field = lastType.getField(preFieldName);
				resolvedPath.add((EntityField<GenericEntity>) field);
				lastType = (EntityType) field.getType();
			}
			targetField = (EntityField<T>) lastType.getField(lastFieldName);
		}

		GenericEntity getTargetEntity(GenericEntity sourceEntity) {
			GenericEntity e = sourceEntity;
			for (EntityField<GenericEntity> field : resolvedPath) {
				e = e.get(field);
				if (e == null)
					return null;
			}
			return e;
		}
	}

	static class FieldGetterImpl<T> extends AbstractFieldResolving<T> implements FieldGetter<T> {
		FieldGetterImpl(FieldPath<T> path) {
			super(path);
		}

		@Override
		public T apply(GenericEntity entity) {
			GenericEntity target = getTargetEntity(entity);
			return target == null ? null : target.get(targetField);
		}
	}

	static abstract class AbstractFieldSetter<F, T> extends AbstractFieldResolving<T> implements FieldSetter<F> {
		AbstractFieldSetter(FieldPath<T> path) {
			super(path);
		}

		@Override
		public abstract void accept(GenericEntity entity, F fromValue);
	}
}
