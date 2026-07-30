package org.qommons.data.values;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.qommons.IterableUtils;
import org.qommons.Transactable;
import org.qommons.Transaction;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.MultiEntryHandle;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;
import org.qommons.data.types.TupleFieldValue;
import org.qommons.ex.CheckedExceptionWrapper;

public interface GenericEntitySet extends Transactable {
	EntityTypeSet getTypes();

	Iterable<GenericEntity> getEntities(String typeName) throws IllegalArgumentException, IOException;

	GenericEntity getEntity(String typeName, Object... id) throws IllegalArgumentException, IOException;

	boolean isMember(GenericEntity entity);

	GenericEntity createEntity(String typeName);

	GenericEntity createEntity(String typeName, Object... ids);

	static void copy(GenericEntitySet source, GenericEntitySet dest) throws IOException {
		try (Transaction t = dest.lockWrite(false, null)) {
			Copying.copy(source, dest);
		}
	}

	static class Copying {
		static void copy(GenericEntitySet source, GenericEntitySet dest) throws IOException {
			for (EntityType srcEntityType : source.getTypes().getEntityTypes()) {
				EntityType destEntityType;
				Collection<? extends EntityField<?>> destFields;
				if (source.getTypes() == dest.getTypes()) {
					destEntityType = srcEntityType;
					destFields = destEntityType.getFields();
				} else {
					destEntityType = dest.getTypes().getEntityType(srcEntityType.getName());
					if (destEntityType == null) {
						continue; // Guess we'll just do what we can
					}
					destFields = new ArrayList<>(srcEntityType.getFields().size());
					for (EntityField<?> srcField : srcEntityType.getFields()) {
						EntityField<?> destField = destEntityType.getField(srcField.getName());
						if (destField == null)
							throw new IllegalArgumentException(
								"Cannot copy entities--no such field " + srcEntityType + "." + srcField.getName() + " in destination");
						else if (srcField.isId() != destField.isId())
							throw new IllegalArgumentException("Cannot copy entities--field " + srcEntityType + "." + srcField.getName()
							+ " is an ID in source or destination, but not both");
						else if ((srcField.getMapping() == null) != (destField.getMapping() == null))
							throw new IllegalArgumentException("Cannot copy entities--field " + srcEntityType + "." + srcField.getName()
							+ " is mapped in source or destination, but not both");
						else if (!typeMatches(srcField.getType(), destField.getType()))
							throw new IllegalArgumentException("Cannot copy entities--source field " + srcField
								+ " does not match type of the destination field: " + destField.getType());
						((Collection<EntityField<?>>) destFields).add(destField);
					}
				}
				for (GenericEntity srcEntity : source.getEntities(srcEntityType.getName())) {
					GenericEntity destEntity = copyEntity(srcEntity, dest);
					Iterator<? extends EntityField<?>> destFieldIter = destFields.iterator();
					for (EntityField<?> field : srcEntityType.getFields()) {
						EntityField<?> destField = destFieldIter.next();
						if (!field.isId() && field.getMapping() == null)
							copyFieldValue(srcEntity, field, destEntity, destField);
					}
				}
			}
		}

		private static GenericEntity copyEntity(GenericEntity srcEntity, GenericEntitySet dest) throws IOException {
			Object[] id = srcEntity.getId();
			int f = 0;
			for (EntityField<?> field : srcEntity.getType().getIdFields()) {
				if (field.getType() instanceof EntityType && id[f] != null)
					id[f] = copyEntity((GenericEntity) id[f], dest);
				f++;
			}
			GenericEntity destEntity = dest.getEntity(srcEntity.getType().getName(), id);
			if (destEntity == null)
				destEntity = dest.createEntity(srcEntity.getType().getName(), id);
			return destEntity;
		}

		private static boolean typeMatches(FieldType<?> srcType, FieldType<?> destType) {
			if (srcType == destType)
				return true;
			else if (srcType instanceof FieldType.SimpleType || srcType == FieldType.BLOB)
				return false;
			else if (srcType instanceof EnumType)
				return destType instanceof EnumType && ((EnumType) srcType).getName().equals(((EnumType) destType).getName());
			else if (srcType instanceof EntityType)
				return destType instanceof EntityType && ((EntityType) srcType).getName().equals(((EntityType) destType).getName());
			else if (srcType instanceof FieldType.ParameterizedType) {
				if (!(destType instanceof FieldType.ParameterizedType))
					return false;
				FieldType.ParameterizedType<?> srcPT = (FieldType.ParameterizedType<?>) srcType;
				FieldType.ParameterizedType<?> destPT = (FieldType.ParameterizedType<?>) destType;
				if (srcPT.getClass() != destPT.getClass() || srcPT.getTypeParameters().size() != destPT.getTypeParameters().size())
					return false;
				for (int p = 0; p < srcPT.getTypeParameters().size(); p++) {
					if (!typeMatches(srcPT.getTypeParameters().get(p), destPT.getTypeParameters().get(p)))
						return false;
				}
				return true;
			} else
				throw new IllegalStateException("Unhandled field type " + srcType);
		}

		private static <F> F copyFieldValue(GenericEntity srcEntity, EntityField<F> srcField, GenericEntity destEntity,
			EntityField<?> destField) throws IOException {
			F srcValue = srcEntity.get(srcField);
			F destValue;
			if (srcValue == null || srcField.getType() instanceof FieldType.SimpleType || srcField.getType() == FieldType.BLOB) {
				destValue = srcValue;
				destEntity.set(destField, destValue);
			} else if (srcField.getType() instanceof EnumType) {
				if (destEntity.getType() == srcEntity.getType())
					destValue = srcValue;
				else {
					EnumValue srcEnumV = (EnumValue) srcValue;
					EnumType destEnumType = destEntity.getType().getTypeSet().getEnumType(srcEnumV.getType().getName());
					if (destEnumType == null)
						throw new IllegalArgumentException("Cannot copy entities--no such enum type in destination: " + srcEnumV.getType());
					destValue = (F) destEnumType.getValue(srcEnumV.getName());
					if (destValue == null)
						throw new IllegalArgumentException("Cannot copy entities--no such enum '" + srcEnumV.getName() + "' in type "
							+ destEnumType.getName() + " in destination");
				}
				destEntity.set(destField, destValue);
			} else if (srcField.getType() instanceof EntityType) {
				destValue = (F) copyEntity((GenericEntity) srcValue, destEntity.getEntitySet());
				destEntity.set(destField, destValue);
			} else if (srcField.getType() instanceof FieldType.TupleType) {
				FieldType.TupleType srcTT = (FieldType.TupleType) srcField.getType();
				if (srcTT.isComplex())
					throw new IllegalArgumentException("Complex types are not supported here: " + srcField);
				TupleFieldValue srcTuple = (TupleFieldValue) srcValue;
				TupleFieldValue destTuple = (TupleFieldValue) destEntity.get(destField);
				boolean tuplesMatch = true;
				if (destTuple == null) {
					tuplesMatch = false;
					destTuple = new TupleFieldValue(srcTuple.length());
				}
				destValue = (F) destTuple;
				for (int c = 0; tuplesMatch && c < srcTT.length(); c++) {
					if (!valuesMatch(srcTuple.get(c), destTuple.get(c)))
						tuplesMatch = false;
				}
				if (!tuplesMatch) {
					GenericEntitySet destEntities = destEntity.getEntitySet();
					for (int c = 0; c < srcTT.length(); c++)
						destTuple.set(c, getDestFieldValue(srcTuple.get(c), destEntities, srcField == destField));
				}
			} else if (srcField.getType() instanceof FieldType.CollectionType) {
				FieldType.CollectionType<?, ?> srcCT = (FieldType.CollectionType<?, ?>) srcField.getType();
				if (srcCT.isComplex())
					throw new IllegalArgumentException("Complex types are not supported here: " + srcField);
				Collection<Object> srcColl = (Collection<Object>) srcEntity.get(srcField);
				Collection<Object> destColl = (Collection<Object>) destEntity.get(destField);
				destValue = (F) destColl;
				if (!collectionsMatch(srcColl, destColl)) {
					if (!destColl.isEmpty())
						destColl.clear();
					GenericEntitySet destEntities = destEntity.getEntitySet();
					for (Object value : srcColl)
						destColl.add(getDestFieldValue(value, destEntities, srcField == destField));
				}
			} else if (srcField.getType() instanceof FieldType.MapType) {
				FieldType.MapType<?, ?, ?> srcMT = (FieldType.MapType<?, ?, ?>) srcField.getType();
				if (srcMT.isComplex())
					throw new IllegalArgumentException("Complex types are not supported here: " + srcField);
				Map<Object, Object> srcMap = (Map<Object, Object>) srcEntity.get(srcField);
				Map<Object, Object> destMap = (Map<Object, Object>) destEntity.get(srcField);
				destValue = (F) destMap;
				if (!collectionsMatch(srcMap.keySet(), destMap.keySet()) && collectionsMatch(srcMap.values(), destMap.values())) {
					destMap.clear();
					GenericEntitySet destEntities = destEntity.getEntitySet();
					for (Map.Entry<Object, Object> entry : srcMap.entrySet())
						destMap.put(//
							getDestFieldValue(entry.getKey(), destEntities, srcField == destField), //
							getDestFieldValue(entry.getValue(), destEntities, srcField == destField));
				}
			} else if (srcField.getType() instanceof FieldType.MultiMapType) {
				FieldType.MultiMapType<?, ?, ?> srcMT = (FieldType.MultiMapType<?, ?, ?>) srcField.getType();
				if (srcMT.isComplex())
					throw new IllegalArgumentException("Complex types are not supported here: " + srcField);
				BetterMultiMap<Object, Object> srcMap = (BetterMultiMap<Object, Object>) srcEntity.get(srcField);
				BetterMultiMap<Object, Object> destMap = (BetterMultiMap<Object, Object>) destEntity.get(srcField);
				destValue = (F) destMap;
				boolean matches = collectionsMatch(srcMap.keySet(), destMap.keySet());
				if (matches) {
					Iterator<? extends MultiEntryHandle<Object, Object>> srcEntries = srcMap.entrySet().iterator();
					Iterator<? extends MultiEntryHandle<Object, Object>> destEntries = destMap.entrySet().iterator();
					while (matches && srcEntries.hasNext())
						matches = collectionsMatch(srcEntries.next().getValues(), destEntries.next().getValues());
				}
				if (!matches) {
					destMap.clear();
					GenericEntitySet destEntities = destEntity.getEntitySet();
					for (MultiEntryHandle<Object, Object> entry : srcMap.entrySet()) {
						try {
							destMap.addAll(//
								getDestFieldValue(entry.getKey(), destEntities, srcField == destField), //
								IterableUtils.map(entry.getValues(), v -> {
									try {
										return getDestFieldValue(v, destEntities, srcField == destField);
									} catch (IOException e) {
										throw new CheckedExceptionWrapper(e);
									}
								}));
						} catch (CheckedExceptionWrapper e) {
							e.throwIfType(IOException.class);
							throw e;
						}
					}
				}
			} else
				throw new IllegalStateException("Unhandled field type: " + srcField.getType());
			return destValue;
		}

		private static boolean collectionsMatch(Collection<?> src, Collection<?> dest) {
			if (src.size() != dest.size())
				return false;
			Iterator<?> destIter = dest.iterator();
			for (Object srcV : src) {
				if (!valuesMatch(srcV, destIter.next()))
					return false;
			}
			return true;
		}

		private static boolean valuesMatch(Object srcV, Object destV) {
			boolean matches;
			if (srcV == null)
				matches = destV == null;
			else if (destV == null)
				matches = false;
			else if (srcV instanceof EnumValue)
				matches = ((EnumValue) srcV).getName().equals(((EnumValue) destV).getName());
			else if (srcV instanceof GenericEntity) {
				// Check the type, because the sub-type may not be the same
				matches = ((GenericEntity) srcV).getType().getName().equals(((GenericEntity) destV).getType().getName());
				if (matches) {
					Object[] srcId = ((GenericEntity) srcV).getId();
					Object[] destId = ((GenericEntity) destV).getId();
					for (int i = 0; matches && i < srcId.length; i++)
						matches = valuesMatch(srcId[i], destId[i]);
				}
			} else // Should be a simple type now
				matches = srcV.equals(destV);
			return matches;
		}

		private static <F> F getDestFieldValue(F srcValue, GenericEntitySet destEntities, boolean sameTypes) throws IOException {
			if (srcValue == null)
				return null;
			else if (srcValue instanceof EnumValue) {
				if (sameTypes)
					return srcValue;
				else {
					EnumValue enumV = (EnumValue) srcValue;
					return (F) destEntities.getTypes().getEnumType(enumV.getType().getName()).getValue(enumV.getName());
				}
			} else if (srcValue instanceof GenericEntity)
				return (F) copyEntity((GenericEntity) srcValue, destEntities);
			else // Should be a simple type now
				return srcValue;
		}
	}
}
