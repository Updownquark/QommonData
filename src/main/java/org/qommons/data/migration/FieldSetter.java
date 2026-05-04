package org.qommons.data.migration;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;

import org.qommons.collect.MultiMap;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.data.types.Blob;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.FileUtils;
import org.qommons.io.LocatedPositionedContent;

public interface FieldSetter<F, T> extends BiConsumer<GenericEntity, F>, FieldResolving<T> {
	public abstract class Abstract<F, T> extends FieldResolving.Abstract<T> implements FieldSetter<F, T> {
		Abstract(FieldPath<T> path) {
			super(path);
		}

		@Override
		public abstract void accept(GenericEntity entity, F fromValue);
	}

	public class Simple<F, T> extends Abstract<F, T> {
		public Simple(FieldPath<T> path) {
			super(path);
		}

		@Override
		public void accept(GenericEntity entity, F fromValue) {
			GenericEntity target = getTargetEntity(entity);
			if (target != null)
				target.set(getTargetField(), fromValue);
		}
	}

	static <F, T> FieldSetter<F, T> parse(EntityType entity, FieldType<F> fromType, CharSequence from,
		LocatedPositionedContent to) throws QonfigInterpretationException {
		FieldPath<T> toPath = FieldPath.parse(entity, to);
		FieldType<?> type = toPath.lastField.getType();
		if (type instanceof FieldType.SimpleType || type instanceof EntityType || type instanceof EnumType) {
			if (!type.isAssignableFrom(fromType))
				throw new QonfigInterpretationException(
					"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to,
					to);
			return new Simple<>(toPath);
		} else if (type == FieldType.BLOB) {
			if (!type.isAssignableFrom(fromType))
				throw new QonfigInterpretationException(
					"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to,
					to);
			return new Abstract<F, T>(toPath) {
				@Override
				public void accept(GenericEntity entity2, F fromValue) {
					GenericEntity target = getTargetEntity(entity2);
					if (target != null) {
						try {
							FileUtils.copy(((Blob) fromValue)::read, ((Blob) target.get(getTargetField()))::write);
						} catch (IOException e) {
							throw new IllegalStateException("Failed to copy blob data", e);
						}
					}
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
		} else if (type instanceof FieldType.MapType) {
			FieldType.MapType<?, ?, ?> mapType = (FieldType.MapType<?, ?, ?>) type;
			if (mapType.isComplex())
				throw new QonfigInterpretationException("Complex types are not supported here: " + mapType, to);
			if (fromType instanceof FieldType.MapType) {
				if (!mapType.keyType.isAssignableFrom(((FieldType.MapType<?, ?, ?>) fromType).keyType)//
					|| !mapType.valueType.isAssignableFrom(((FieldType.MapType<?, ?, ?>) fromType).valueType))
					throw new QonfigInterpretationException("Type " + fromType + " of " + entity + "." + from + " cannot be copied to type "
						+ type + " of " + entity + "." + to, to);
				return new Abstract<F, T>(toPath) {
					@Override
					public void accept(GenericEntity entity2, F fromValue) {
						GenericEntity target = getTargetEntity(entity2);
						Map<Object, Object> destMap = (Map<Object, Object>) target.get(getTargetField());
						destMap.putAll((Map<?, ?>) fromValue);
					}
				};
			} else
				throw new QonfigInterpretationException(
					"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to,
					to);
		} else if (type instanceof FieldType.MultiMapType) {
			FieldType.MultiMapType<?, ?, ?> mapType = (FieldType.MultiMapType<?, ?, ?>) type;
			if (mapType.isComplex())
				throw new QonfigInterpretationException("Complex types are not supported here: " + mapType, to);
			if (fromType instanceof FieldType.MultiMapType) {
				if (!mapType.keyType.isAssignableFrom(((FieldType.MultiMapType<?, ?, ?>) fromType).keyType)//
					|| !mapType.valueType.isAssignableFrom(((FieldType.MultiMapType<?, ?, ?>) fromType).valueType))
					throw new QonfigInterpretationException("Type " + fromType + " of " + entity + "." + from + " cannot be copied to type "
						+ type + " of " + entity + "." + to, to);
				return new Abstract<F, T>(toPath) {
					@Override
					public void accept(GenericEntity entity2, F fromValue) {
						GenericEntity target = getTargetEntity(entity2);
						MultiMap<Object, Object> destMap = (MultiMap<Object, Object>) target.get(getTargetField());
						destMap.putAll((MultiMap<?, ?>) fromValue);
					}
				};
			} else if (fromType instanceof FieldType.MapType) {
				if (!mapType.keyType.isAssignableFrom(((FieldType.MapType<?, ?, ?>) fromType).keyType)//
					|| !mapType.valueType.isAssignableFrom(((FieldType.MapType<?, ?, ?>) fromType).valueType))
					throw new QonfigInterpretationException("Type " + fromType + " of " + entity + "." + from + " cannot be copied to type "
						+ type + " of " + entity + "." + to, to);
				return new Abstract<F, T>(toPath) {
					@Override
					public void accept(GenericEntity entity2, F fromValue) {
						GenericEntity target = getTargetEntity(entity2);
						MultiMap<Object, Object> destMap = (MultiMap<Object, Object>) target.get(getTargetField());
						destMap.putAll((Map<?, ?>) fromValue);
					}
				};
			} else
				throw new QonfigInterpretationException(
					"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to,
					to);
		} else
			throw new QonfigInterpretationException(
				"Type " + fromType + " of " + entity + "." + from + " cannot be copied to type " + type + " of " + entity + "." + to, to);
	}
}