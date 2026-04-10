package org.qommons.data.mapping;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.qommons.ClassMap;
import org.qommons.Named;
import org.qommons.collect.BetterSortedList;
import org.qommons.collect.MultiMap;
import org.qommons.collect.SortedMultiMap;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;

public class EntityTypeSetMapping {
	private final EntityTypeSet theGenericTypes;
	private final NavigableMap<String, EntityTypeMapping<?>> theEntityTypes;
	private final NavigableMap<String, EnumTypeMapping<?>> theEnumTypes;
	private final ClassMap<EntityTypeMapping<?>> theEntityTypeHierarchy;

	public EntityTypeSetMapping(EntityTypeSet genericTypes, NavigableMap<String, EntityTypeMapping<?>> entityTypes,
		NavigableMap<String, EnumTypeMapping<?>> enumTypes) {
		theGenericTypes = genericTypes;
		theEntityTypes = entityTypes;
		theEnumTypes = enumTypes;
		theEntityTypeHierarchy = new ClassMap<>();
		for (EntityTypeMapping<?> entity : entityTypes.values())
			theEntityTypeHierarchy.put(entity.getRealType(), entity);
		theEntityTypeHierarchy.seal();
	}

	public EntityTypeSet getGenericTypes() {
		return theGenericTypes;
	}

	public NavigableMap<String, EntityTypeMapping<?>> getEntityTypes() {
		return theEntityTypes;
	}

	public NavigableMap<String, EnumTypeMapping<?>> getEnumTypes() {
		return theEnumTypes;
	}

	public ClassMap<EntityTypeMapping<?>> getEntityTypeHierarchy() {
		return theEntityTypeHierarchy;
	}

	public static class TypeSetMappingException extends Exception {
		public final MappedTypeSetDiff diff;

		public TypeSetMappingException(MappedTypeSetDiff diff) {
			this.diff = diff;
		}
	}

	public static class MappedTypeSetDiff {
		public final NavigableSet<MappedEntityDiff> entityTypes;
		public final NavigableSet<MappedEnumDiff> enumTypes;

		public MappedTypeSetDiff(NavigableSet<MappedEntityDiff> entityTypes, NavigableSet<MappedEnumDiff> enumTypes) {
			this.entityTypes = entityTypes;
			this.enumTypes = enumTypes;
		}

		public StringBuilder print(StringBuilder str) {
			for (MappedEnumDiff diff : enumTypes)
				diff.print(str.append("\n\t"));

			for (MappedEntityDiff diff : entityTypes)
				diff.print(str.append("\n\t"));

			return str;
		}
	}

	public static class MappedEntityDiff implements Named {
		public final EntityType genericType;
		public final Class<?> codeType;
		public final String codeEntityName;
		public final NavigableSet<MappedFieldDiff> fields;

		public MappedEntityDiff(EntityType genericType, Class<?> codeType, String codeEntityName, NavigableSet<MappedFieldDiff> fields) {
			this.genericType = genericType;
			this.codeType = codeType;
			this.codeEntityName = codeEntityName;
			this.fields = fields;
		}

		@Override
		public String getName() {
			if (codeEntityName != null)
				return codeEntityName;
			else
				return genericType.getName();
		}

		public void print(StringBuilder str) {
			if (genericType == null)
				str.append("Entity ").append(codeEntityName).append("(class ").append(codeType.getName())
				.append(") is present in code, but not in documentation");
			else if (codeType == null)
				str.append("Entity ").append(genericType.getName()).append(" is present in documentation, but not in code");
			else { // Must be one or more fields that are different
				str.append("Entity ").append(codeEntityName).append("(class ").append(codeType.getName()).append("):");
				for (MappedFieldDiff field : fields) {
					field.print(str.append("\n\t\t"));
				}
			}
		}
	}

	public static class MappedFieldDiff implements Named {
		public final EntityField<?> genericField;
		public final Method getter;
		public final String codeFieldName;
		public final String description;

		public MappedFieldDiff(EntityField<?> genericField, Method getter, String codeFieldName, String description) {
			this.genericField = genericField;
			this.getter = getter;
			this.codeFieldName = codeFieldName;
			this.description = description;
		}

		@Override
		public String getName() {
			if (codeFieldName != null)
				return codeFieldName;
			else
				return genericField.getName();
		}

		public void print(StringBuilder str) {
			if (getter != null) {
				str.append("Field ").append(codeFieldName).append(" (getter ");
				printType(str, getter.getGenericReturnType());
				str.append(' ').append(getter.getName()).append("()): ");
			} else
				str.append("Field ").append(genericField.getName()).append(" (").append(genericField.getType()).append("): ");
			str.append(description);
		}
	}

	public static class MappedEnumDiff implements Named {
		public final EnumType genericType;
		public final Class<? extends Enum<?>> codeType;

		public MappedEnumDiff(EnumType genericType, Class<? extends Enum<?>> codeType) {
			this.genericType = genericType;
			this.codeType = codeType;
		}

		@Override
		public String getName() {
			if (genericType != null)
				return genericType.getName();
			else
				return codeType.getSimpleName();
		}

		public void print(StringBuilder str) {
			if (genericType == null)
				str.append("Enum ").append(codeType.getSimpleName()).append(" (class ").append(codeType.getName())
				.append("): Present in code, but not in documentation");
			else if (codeType == null)
				str.append("Enum ").append(genericType.getName()).append(": Present in documentation, but not in code");
			else {
				str.append("Enum ").append(codeType.getSimpleName()).append(" (class ").append(codeType.getName()).append("):");
				Set<String> codeValues = new HashSet<>();
				for (Enum<?> value : codeType.getEnumConstants()) {
					codeValues.add(value.name());
					if (genericType.getValue(value.name()) == null)
						str.append("\n\t\tValue ").append(value.name()).append(": Present in code, but not in documentation");
				}
				for (EnumValue value : genericType.getValues()) {
					if (!codeValues.contains(value.getName()))
						str.append("\n\t\tValue ").append(value.getName()).append(": Present in documentation, but not in code");
				}
			}
		}
	}

	public interface EntityMappingScheme<T> {
		T isEntity(Class<?> type);

		String getEntityName(Class<?> type, T entity);

		String getField(T entity, Method getter);
	}

	public static EntityTypeSetMapping parseTypeSet(EntityTypeSet genericTypes, Set<Class<?>> entityTypes,
		EntityMappingScheme<?> entityMapping) throws TypeSetMappingException {
		Parsing<?> parsing = new Parsing<>(genericTypes, entityMapping);
		parsing.parse(entityTypes);
		MappedTypeSetDiff diff = parsing.getDifferences();
		if (diff != null)
			throw new TypeSetMappingException(diff);
		return parsing.getMappedTypeSet();
	}

	private static class Parsing<T> {
		private final EntityTypeSet theGenericTypes;
		private final EntityTypeSetMapping theMappedTypeSet;
		private final EntityMappingScheme<T> theEntityMapping;
		private final NavigableMap<String, EntityTypeMapping<?>> theMappedEntities;
		private final NavigableMap<String, EnumTypeMapping<?>> theMappedEnums;
		private final NavigableMap<String, MappedEntityDiff> theEntityDiffs;
		private final NavigableMap<String, MappedEnumDiff> theEnumDiffs;
		private final Map<String, NavigableSet<MappedFieldDiff>> theFieldDiffs;

		Parsing(EntityTypeSet genericTypes, EntityMappingScheme<T> entityMapping) {
			theGenericTypes = genericTypes;
			theEntityMapping = entityMapping;
			theMappedEntities = new TreeMap<>();
			theMappedEnums = new TreeMap<>();
			theMappedTypeSet = new EntityTypeSetMapping(genericTypes, Collections.unmodifiableNavigableMap(theMappedEntities),
				Collections.unmodifiableNavigableMap(theMappedEnums));
			theEntityDiffs = new TreeMap<>();
			theEnumDiffs = new TreeMap<>();
			theFieldDiffs = new HashMap<>();
		}

		public MappedTypeSetDiff getDifferences() {
			if (theEntityDiffs.isEmpty() && theEnumDiffs.isEmpty())
				return null;
			return new MappedTypeSetDiff(//
				Collections.unmodifiableNavigableSet(theEntityDiffs.values().stream().collect(Collectors.toCollection(TreeSet::new))),
				Collections.unmodifiableNavigableSet(theEnumDiffs.values().stream().collect(Collectors.toCollection(TreeSet::new))));
		}

		public EntityTypeSetMapping getMappedTypeSet() {
			return theMappedTypeSet;
		}

		void parse(Set<? extends Class<?>> entityTypes) {
			for (Class<?> et : entityTypes) {
				T type = theEntityMapping.isEntity(et);
				if (type == null)
					throw new IllegalArgumentException("Supplied entity type " + et.getName() + " is not recognized as an entity");
				mapEntity(et, type);
			}
			for (EnumType enumType : theGenericTypes.getEnumTypes()) {
				if (!theMappedEnums.containsKey(enumType.getName()))
					theEnumDiffs.put(enumType.getName(), new MappedEnumDiff(enumType, null));
			}
			for (EntityType entity : theGenericTypes.getEntityTypes()) {
				if (!theMappedEntities.containsKey(entity.getName()))
					theEntityDiffs.put(entity.getName(), new MappedEntityDiff(entity, null, null, Collections.emptyNavigableSet()));
			}
		}

		private void mapEntity(Class<?> codeType, T entity) {
			String entityName = theEntityMapping.getEntityName(codeType, entity);
			if (!theMappedEntities.containsKey(entityName) && !theEntityDiffs.containsKey(entityName)) {
				EntityType genericType = theGenericTypes.getEntityType(entityName);
				if (genericType == null)
					theEntityDiffs.put(entityName, new MappedEntityDiff(null, codeType, entityName, Collections.emptyNavigableSet()));
				else {
					NavigableMap<String, EntityFieldMapping<?, ?>> fields = new TreeMap<>();
					EntityTypeMapping<?> mapping = new EntityTypeMapping<>(theMappedTypeSet, genericType, codeType,
						Collections.unmodifiableNavigableMap(fields));
					theMappedEntities.put(genericType.getName(), mapping);
					for (Method method : codeType.getMethods()) {
						if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
							String fieldName = theEntityMapping.getField(entity, method);
							if (fieldName != null) {
								EntityField<?> field = genericType.getField(fieldName);
								if (field == null) {
									theFieldDiffs
									.computeIfAbsent(genericType.getName(), __ -> new TreeSet<>(Named.DISTINCT_NUMBER_TOLERANT))
									.add(new MappedFieldDiff(null, method, fieldName, "Present in code, but not in documentation"));
								} else {
									fields.put(fieldName, new EntityFieldMapping<>(field, method));
									checkField(mapping, method, field);
								}
							}
						}
					}
					for (EntityField<?> field : genericType.getFields()) {
						if (!fields.containsKey(field.getName())) {
							theFieldDiffs.computeIfAbsent(genericType.getName(), __ -> new TreeSet<>(Named.DISTINCT_NUMBER_TOLERANT))
							.add(new MappedFieldDiff(field, null, null, "Present in documentation, but not in code"));
						}
					}
					NavigableSet<MappedFieldDiff> fieldDiffs = theFieldDiffs.remove(genericType.getName());
					if (fieldDiffs != null) {
						theEntityDiffs.put(genericType.getName(), new MappedEntityDiff(genericType, codeType, genericType.getName(),
							Collections.unmodifiableNavigableSet(fieldDiffs)));
					}
				}
			}
		}

		private void checkField(EntityTypeMapping<?> entity, Method method, EntityField<?> field) {
			Type type = method.getGenericReturnType();
			if (!checkFieldType(method.getGenericReturnType())) {
				theFieldDiffs.computeIfAbsent(field.getOwner().getName(), __ -> new TreeSet<>()).add(
					new MappedFieldDiff(field, method, field.getName(), "Unhandled field type: " + printType(new StringBuilder(), type)));
			}
		}

		private boolean checkFieldType(Type type) {
			if (type instanceof Class) {
				if (((Class<?>) type).isEnum()) {
					mapEnum((Class<? extends Enum<?>>) type);
				} else {
					FieldType.SimpleType<?> simple = FieldType.SimpleType.get((Class<?>) type);
					if (simple == null) {
						T entity = theEntityMapping.isEntity((Class<?>) type);
						if (entity != null)
							mapEntity((Class<?>) type, entity);
						else {
							return false;
						}
					}
				}
			} else if (type instanceof ParameterizedType) {
				ParameterizedType pt = (ParameterizedType) type;
				if (!(pt.getRawType() instanceof Class))
					return false;
				Class<?> raw = (Class<?>) pt.getRawType();
				Type[] params = pt.getActualTypeArguments();
				if (SortedMultiMap.class.isAssignableFrom(raw)) {//
				} else if (MultiMap.class.isAssignableFrom(raw)) {//
				} else if (SortedMap.class.isAssignableFrom(raw)) {//
				} else if (Map.class.isAssignableFrom(raw)) {//
				} else if (SortedSet.class.isAssignableFrom(raw)) {//
				} else if (BetterSortedList.class.isAssignableFrom(raw)) {//
				} else if (Set.class.isAssignableFrom(raw)) {//
				} else if (!Collection.class.isAssignableFrom(raw)) {//
					return false;
				}
				for (Type param : params) {
					if (!checkFieldType(param))
						return false;
				}
			} else
				return false;
			return true;
		}

		private void mapEnum(Class<? extends Enum<?>> enumType) {
			String name = enumType.getSimpleName();
			if (theMappedEnums.containsKey(name) || theEnumDiffs.containsKey(name)) {
				return; // Already taken care of
			}
			EnumType genericEnum = theGenericTypes.getEnumType(name);
			if (genericEnum == null) {
				theEnumDiffs.put(name, new MappedEnumDiff(null, enumType));
			} else {
				Enum<?>[] codeValues = enumType.getEnumConstants();
				if (codeValues.length != genericEnum.getValues().size()) {
					theEnumDiffs.put(name, new MappedEnumDiff(null, enumType));
				} else {
					for (Enum<?> value : codeValues) {
						if (genericEnum.getValue(value.name()) == null) {
							theEnumDiffs.put(name, new MappedEnumDiff(null, enumType));
							break;
						}
					}
				}
			}
		}
	}

	private static StringBuilder printType(StringBuilder str, Type type) {
		if (type instanceof Class)
			str.append(((Class<?>) type).getName());
		else if (type instanceof ParameterizedType) {
			printType(str, ((ParameterizedType) type).getRawType());
			str.append('<');
			boolean first = true;
			for (Type param : ((ParameterizedType) type).getActualTypeArguments()) {
				if (first)
					first = false;
				else
					str.append(", ");
				printType(str, param);
			}
			str.append('>');
		} else
			str.append(type);
		return str;
	}
}
