package org.qommons.data.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.qommons.Named;
import org.qommons.collect.BetterCollection;
import org.qommons.collect.BetterMap;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.MultiMap;
import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;
import org.qommons.data.types.FieldType.SimpleType;
import org.qommons.data.types.modifiable.ModifiableEntityType;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.ex.ExFunction;
import org.qommons.io.FilePosition;
import org.qommons.io.TextParseException;

public class MigrationUtil {
	public static class MigrationDiff {
		public final Set<MigrationSetDef> dataSourceAppliedMigration;
		public final Set<MigrationSetDef> unrecognizedMigrations;
		public final Set<MigrationSet> unappliedMigrations;

		public MigrationDiff(Set<MigrationSetDef> dataSourceAppliedMigration, Set<MigrationSetDef> unrecognizedMigrations,
			Set<MigrationSet> unappliedMigrations) {
			this.dataSourceAppliedMigration = dataSourceAppliedMigration;
			this.unrecognizedMigrations = unrecognizedMigrations;
			this.unappliedMigrations = unappliedMigrations;
		}
	}

	public static class SchemaDiff {
		public final List<EntityTypeDiff> entityDiffs;
		public final List<EnumTypeDiff> enumDiffs;

		public SchemaDiff(List<EntityTypeDiff> entityDiffs, List<EnumTypeDiff> enumDiffs) {
			this.entityDiffs = entityDiffs;
			this.enumDiffs = enumDiffs;
		}

		public String print(String leftName, String rightName) {
			return print(new StringBuilder(), leftName, rightName).toString();
		}

		public StringBuilder print(StringBuilder str, String leftName, String rightName) {
			for (EntityTypeDiff diff : entityDiffs) {
				if (str.length() > 0)
					str.append('\n');
				str.append(diff.print(str, leftName, rightName));
			}
			for (EnumTypeDiff diff : enumDiffs) {
				if (str.length() > 0)
					str.append('\n');
				str.append(diff.print(str, leftName, rightName));
			}
			return str;
		}

		@Override
		public String toString() {
			return print("left", "right");
		}
	}

	public static class EntityTypeDiff {
		public final EntityType leftType;
		public final EntityType rightType;
		public final boolean baseDiff;
		public final Map<String, EntityFieldDiff> fields;

		public EntityTypeDiff(EntityType leftType, EntityType rightType, boolean baseDiff, Map<String, EntityFieldDiff> fields) {
			this.leftType = leftType;
			this.rightType = rightType;
			this.baseDiff = baseDiff;
			this.fields = fields;
		}

		public StringBuilder print(StringBuilder str, String leftName, String rightName) {
			if (leftType == null)
				str.append("Entity Type '").append(rightType.getName()).append("' is found in ").append(rightName).append(", but not in ")
				.append(leftName);
			else if (rightType == null)
				str.append("Entity Type '").append(leftType.getName()).append("' is found in ").append(leftName).append(", but not in ")
				.append(rightName);
			else {
				str.append("Entity Type '").append(leftType.getName()).append("':");
				if (baseDiff) {
					if (leftType.getSuperTypes().isEmpty()) {
						if (rightType.getSuperTypes().isEmpty()) {
							boolean idDiff = leftType.getIdFields().size() != rightType.getIdFields().size();
							if (!idDiff) {
								for (int i = 0; i < leftType.getIdFields().size(); i++) {
									if (!leftType.getIdFields().get(i).getName().equals(rightType.getIdFields().get(i).getName())) {
										idDiff = true;
										break;
									}
								}
							}
							if (idDiff) {
								str.append("\n\tID is ");
								for (int i = 0; i < leftType.getIdFields().size(); i++) {
									if (i > 0)
										str.append(',');
									str.append(leftType.getIdFields().get(i).getName());
								}
								str.append(" in ").append(leftName).append(", but ");
								for (int i = 0; i < rightType.getIdFields().size(); i++) {
									if (i > 0)
										str.append(',');
									str.append(rightType.getIdFields().get(i).getName());
								}
								str.append(" in ").append(rightName);
							}
						} else
							str.append("\n\tRoot type in ").append(leftName).append(" but extends ").append(rightType.getSuperTypes())
							.append(" in ").append(rightName);
					} else if (rightType.getSuperTypes().isEmpty())
						str.append("\n\tRoot type in ").append(rightName).append(" but extends ").append(leftType.getSuperTypes())
						.append(" in ").append(leftName);
					else {
						Set<String> supers = new HashSet<>();
						for (EntityType sup : leftType.getSuperTypes())
							supers.add(sup.getName());
						for (EntityType sup : rightType.getSuperTypes()) {
							if (!supers.contains(sup.getName())) {
								str.append("\n\tExtends ").append(leftType.getSuperTypes()).append(" in ").append(leftName)//
								.append(", but ").append(rightType.getSuperTypes()).append(" in ").append(rightName);
								break;
							}
						}
					}
				} else

					for (EntityFieldDiff field : fields.values()) {
						field.print(str.append("\n\t"), leftName, rightName);
					}
			}
			return str;
		}

		@Override
		public String toString() {
			return print(new StringBuilder(), "left", "right").toString();
		}
	}

	public static class EntityFieldDiff {
		public final EntityField<?> leftField;
		public final EntityField<?> rightField;

		public EntityFieldDiff(EntityField<?> leftField, EntityField<?> rightField) {
			this.leftField = leftField;
			this.rightField = rightField;
		}

		public StringBuilder print(StringBuilder str, String leftName, String rightName) {
			if (leftField == null)
				str.append("Field ").append(rightField.getName()).append('(').append(rightField.getType()).append(") appears in ")
				.append(rightName).append(", but not in ").append(leftName);
			else if (rightField == null)
				str.append("Field ").append(leftField.getName()).append('(').append(leftField.getType()).append(") appears in ")
				.append(leftName).append(", but not in ").append(rightName);
			else// Currently, the only difference identically-named fields can have is their type
				str.append("Field ").append(leftField.getName()).append(" has type ").append(leftField.getType()).append(" in ")
				.append(leftName).append(", but ").append(rightField.getType()).append(" in ").append(rightName);
			return str;
		}

		@Override
		public String toString() {
			return print(new StringBuilder(), "left", "right").toString();
		}
	}

	public static class EnumTypeDiff {
		public final EnumType leftType;
		public final EnumType rightType;
		public final List<EnumValueDiff> values;

		public EnumTypeDiff(EnumType leftType, EnumType rightType, List<EnumValueDiff> values) {
			this.leftType = leftType;
			this.rightType = rightType;
			this.values = values;
		}

		public StringBuilder print(StringBuilder str, String leftName, String rightName) {
			if (leftType == null)
				str.append("Enum Type '").append(rightType.getName()).append("' is found in ").append(rightName).append(", but not in ")
				.append(leftName);
			else if (rightType == null)
				str.append("Enum Type '").append(leftType.getName()).append("' is found in ").append(leftName).append(", but not in ")
				.append(rightName);
			else {
				str.append("Enum type '").append(leftType.getName()).append("':");
				for (EnumValueDiff diff : values)
					diff.print(str.append("\n\t"), leftName, rightName);
			}
			return str;
		}

		@Override
		public String toString() {
			return print(new StringBuilder(), "left", "right").toString();
		}
	}

	public static class EnumValueDiff {
		public final EnumValue value;
		public final boolean isInLeft;

		public EnumValueDiff(EnumValue value, boolean isInDataSource) {
			this.value = value;
			this.isInLeft = isInDataSource;
		}

		public StringBuilder print(StringBuilder str, String leftName, String rightName) {
			return str.append("Value '").append(value.getName()).append(" is found in ").append(isInLeft ? leftName : rightName)
				.append(" but not in ").append(isInLeft ? rightName : leftName);
		}

		@Override
		public String toString() {
			return print(new StringBuilder(), "left", "right").toString();
		}
	}

	public static MigrationDiff diffMigrations(BetterSortedSet<MigrationSetDef> dataSourceMigrations,
		BetterSortedSet<MigrationSet> codeMigrations) {
		Set<MigrationSetDef> applied = new LinkedHashSet<>();
		Map<MigrationSetDef, MigrationSetDef> unrecognized = new LinkedHashMap<>();
		Set<MigrationSet> unapplied = new LinkedHashSet<>();
		for (MigrationSetDef dsMig : dataSourceMigrations)
			unrecognized.put(dsMig, dsMig);
		for (MigrationSet migration : codeMigrations) {
			MigrationSetDef dsMig = unrecognized.remove(migration);
			if (dsMig == null)
				unapplied.add(migration);
			else
				applied.add(dsMig);
		}
		if (applied.isEmpty() && unrecognized.isEmpty() && unapplied.isEmpty())
			return null;
		return new MigrationDiff(Collections.unmodifiableSet(applied), Collections.unmodifiableSet(unrecognized.keySet()),
			Collections.unmodifiableSet(unapplied));
	}

	public static SchemaDiff diffSchemata(EntityTypeSet leftTypes, EntityTypeSet rightTypes) {
		Set<EntityType> rightEntities = new LinkedHashSet<>(rightTypes.getEntityTypes());
		List<EntityTypeDiff> entityDiffs = new ArrayList<>();
		for (EntityType leftEntity : leftTypes.getEntityTypes()) {
			EntityType rightEntity = rightTypes.getEntityType(leftEntity.getName());
			if (rightEntity != null) {
				rightEntities.remove(rightEntity);
				EntityTypeDiff etDiff = diffEntityType(leftEntity, rightEntity);
				if (etDiff != null)
					entityDiffs.add(etDiff);
			} else
				entityDiffs.add(new EntityTypeDiff(leftEntity, null, true, Collections.emptyMap()));
		}
		for (EntityType rightEntity : rightEntities)
			entityDiffs.add(new EntityTypeDiff(null, rightEntity, true, Collections.emptyMap()));

		Set<EnumType> rightEnums = new LinkedHashSet<>(rightTypes.getEnumTypes());
		List<EnumTypeDiff> enumDiffs = new ArrayList<>();
		for (EnumType leftEnum : leftTypes.getEnumTypes()) {
			EnumType rightEnum = rightTypes.getEnumType(leftEnum.getName());
			if (rightEnum != null) {
				rightEnums.remove(rightEnum);
				EnumTypeDiff enumDiff = diffEnumType(leftEnum, rightEnum);
				if (enumDiff != null)
					enumDiffs.add(enumDiff);
			} else
				enumDiffs.add(new EnumTypeDiff(leftEnum, null, Collections.emptyList()));
		}
		for (EnumType rightEnum : rightEnums)
			enumDiffs.add(new EnumTypeDiff(null, rightEnum, Collections.emptyList()));

		if (!entityDiffs.isEmpty() || !enumDiffs.isEmpty())
			return new SchemaDiff(Collections.unmodifiableList(entityDiffs), Collections.unmodifiableList(enumDiffs));
		else
			return null;
	}

	public static EntityTypeDiff diffEntityType(EntityType leftType, EntityType rightType) {
		boolean baseDiff = false;
		if (leftType.getSuperTypes().size() != rightType.getSuperTypes().size())
			baseDiff = true;
		else if (leftType.getSuperTypes().isEmpty()) {
			if (leftType.getIdFields().size() == rightType.getIdFields().size()) {
				for (int f = 0; f < leftType.getIdFields().size(); f++) {
					if (!leftType.getIdFields().get(f).getName().equals(rightType.getIdFields().get(f).getName())) {
						baseDiff = true;
						break;
					}
				}
			} else
				baseDiff = true;
		} else {
			Set<String> supers = new HashSet<>();
			for (EntityType sup : leftType.getSuperTypes())
				supers.add(sup.getName());
			for (EntityType sup : rightType.getSuperTypes()) {
				if (!supers.contains(sup.getName())) {
					baseDiff = true;
					break;
				}
			}
		}
		Set<EntityField<?>> rightFields = new LinkedHashSet<>(rightType.getFields());
		Map<String, EntityFieldDiff> fieldDiffs = new LinkedHashMap<>();
		for (EntityField<?> leftField : leftType.getFields()) {
			EntityField<?> rightField = rightType.getField(leftField.getName());
			if (rightField != null) {
				rightFields.remove(rightField);
				if (isDifferent(leftField, rightField))
					fieldDiffs.put(leftField.getName(), new EntityFieldDiff(leftField, rightField));
			} else
				fieldDiffs.put(leftField.getName(), new EntityFieldDiff(leftField, null));
		}
		for (EntityField<?> rightField : rightFields)
			fieldDiffs.put(rightField.getName(), new EntityFieldDiff(null, rightField));
		if (baseDiff || !rightFields.isEmpty())
			return new EntityTypeDiff(leftType, rightType, baseDiff, Collections.unmodifiableMap(fieldDiffs));
		else
			return null;
	}

	public static boolean isDifferent(EntityField<?> leftField, EntityField<?> rightField) {
		return !fieldTypesEqual(leftField.getType(), rightField.getType());
	}

	private static boolean fieldTypesEqual(FieldType<?> leftType, FieldType<?> rightType) {
		if (leftType.getClass() != rightType.getClass())
			return false;
		else if (leftType instanceof FieldType.SimpleType)
			return leftType.equals(rightType);
		else if (leftType instanceof FieldType.ParameterizedType) {
			FieldType.ParameterizedType<?> leftPT = (FieldType.ParameterizedType<?>) leftType;
			FieldType.ParameterizedType<?> rightPT = (FieldType.ParameterizedType<?>) rightType;
			if (!leftPT.rawTypesEqual(rightPT))
				return false;
			for (int i = 0; i < leftPT.getTypeParameters().size(); i++) {
				if (!fieldTypesEqual(leftPT.getTypeParameters().get(i), rightPT.getTypeParameters().get(i)))
					return false;
			}
			return true;
		} else if (leftType instanceof Named)
			return ((Named) leftType).getName().equals(((Named) rightType).getName());
		else
			return false;
	}

	public static EnumTypeDiff diffEnumType(EnumType leftType, EnumType rightType) {
		Set<EnumValue> rightValues = new LinkedHashSet<>(rightType.getValues());
		List<EnumValueDiff> diffs = new ArrayList<>();
		for (EnumValue leftValue : leftType.getValues()) {
			EnumValue rightValue = rightType.getValue(leftValue.getName());
			if (rightValue != null) {
				rightValues.remove(rightValue);
			} else
				diffs.add(new EnumValueDiff(leftValue, true));
		}
		for (EnumValue rightValue : rightValues)
			diffs.add(new EnumValueDiff(rightValue, false));
		return diffs.isEmpty() ? null : new EnumTypeDiff(leftType, rightType, Collections.unmodifiableList(diffs));
	}

	public static void applyMigrationSet(MigratableDataSet dataSet, MigrationSet migrationSet)
		throws IOException, TextParseException, MigrationException, DataSetModificationException {
		Map<String, CustomMigrationComponent> migrators = new LinkedHashMap<>();
		for (ConfigurableCustomMigrator<?> m : migrationSet.getMigrators().values()) {
			try {
				migrators.put(m.getName(), m.migrator.newInstance());
			} catch (InstantiationException | IllegalAccessException | RuntimeException e) {
				throw new MigrationException("Migrator " + m + " could not be instantiated",
					m.configuration.getNamePosition().getPosition(0), e);
			}
		}
		migrators = Collections.unmodifiableMap(migrators);
		for (ConfigurableCustomMigrator<?> m : migrationSet.getMigrators().values()) {
			try {
				migrators.get(m).init(migrationSet, m.configuration, migrators);
			} catch (RuntimeException e) {
				throw new MigrationException("Migrator " + m + " could not be initialized",
					m.configuration.getNamePosition().getPosition(0), e);
			}
		}
		for (Migration migration : migrationSet.getMigrations()) {
			migration.apply(dataSet, migrators);
		}
		dataSet.migrationApplied(migrationSet.toDef());
	}

	public static FieldType parseFieldType(String text, EntityTypeSet types, String creatingEntity, FilePosition source,
		ExFunction<String, ModifiableEntityType, MigrationException> uncreated) throws MigrationException {
		int paramIdx = text.indexOf('<');
		if (paramIdx >= 0) {
			if (text.charAt(text.length() - 1) != '>')
				throw new MigrationException("Terminating '>' expected", source);
			String rawType = text.substring(0, paramIdx);
			int commaIdx = text.indexOf(',', paramIdx + 1);
			FieldType<?> firstType, secondType;
			if (commaIdx >= 0) {
				firstType = parseFieldType(text.substring(paramIdx + 1, commaIdx), types, creatingEntity, source, null);
				secondType = parseFieldType(text.substring(commaIdx + 1, text.length() - 1).trim(), types, creatingEntity, source, null);
			} else {
				firstType = parseFieldType(text.substring(paramIdx + 1, text.length() - 1), types, creatingEntity, source, null);
				secondType = null;
			}
			int expectedParamCount;
			boolean distinct = false, sorted = false, multiValue = false;
			switch (rawType) {
			case "List":
				expectedParamCount = 1;
				break;
			case "Set":
				expectedParamCount = 1;
				distinct = true;
				break;
			case "SortedList":
				expectedParamCount = 1;
				sorted = true;
				break;
			case "SortedSet":
				expectedParamCount = 1;
				distinct = sorted = true;
				break;
			case "Map":
				expectedParamCount = 2;
				break;
			case "SortedMap":
				expectedParamCount = 2;
				sorted = true;
				break;
			case "MultiMap":
				expectedParamCount = 2;
				multiValue = true;
				break;
			case "SortedMultiMap":
				expectedParamCount = 2;
				sorted = multiValue = true;
				break;
			default:
				throw new MigrationException("Unrecognized raw type '" + rawType + "'", source);
			}
			if (expectedParamCount == 1)
				return new FieldType.CollectionType<>(firstType, sorted, distinct);
			else if (multiValue)
				return new FieldType.MultiMapType<>(firstType, secondType, sorted);
			else
				return new FieldType.MapType<>(firstType, secondType, sorted);
		}
		switch (text) {
		case "boolean":
			return SimpleType.BOOLEAN;
		case "char":
			return SimpleType.CHAR;
		case "byte":
			return SimpleType.BYTE;
		case "short":
			return SimpleType.SHORT;
		case "int":
			return SimpleType.INT;
		case "long":
			return SimpleType.LONG;
		case "float":
			return SimpleType.FLOAT;
		case "double":
			return SimpleType.DOUBLE;
		case "String":
			return SimpleType.STRING;
		case "Instant":
			return SimpleType.INSTANT;
		case "Duration":
			return SimpleType.DURATION;
		}
		if (text.equals(creatingEntity))
			return FieldType.SELF;
		EntityType entity = types.getEntityType(text);
		if (entity != null)
			return entity;
		EnumType enumType = types.getEnumType(text);
		if (enumType != null)
			return enumType;
		if (uncreated != null) {
			entity = uncreated.apply(text);
			if (entity != null)
				return entity;
		}
		throw new MigrationException("Unrecognized type '" + text + "'", source);
	}

	public static <F> F parseFieldValue(CharSequence text, FieldType<F> fieldType, GenericEntitySet entities, Supplier<FilePosition> source)
		throws MigrationException {
		if ("null".equals(text))
			return null;
		else if (fieldType instanceof EnumType) {
			EnumType enumType = (EnumType) fieldType;
			EnumValue value = enumType.getValue(text.toString());
			if (value == null)
				throw new MigrationException("No such enum value " + enumType + "." + text, source.get());
			return (F) value;
		} else if (fieldType instanceof FieldType.SimpleType) {
			return ((FieldType.SimpleType<F>) fieldType).parse(text.toString(), source);
		} else if (fieldType instanceof FieldType.CollectionType) {
			return (F) parseCollection(text, (FieldType.CollectionType<?, ?>) fieldType, entities, source);
		} else if (fieldType instanceof FieldType.MapType) {
			return (F) parseMap(text, (FieldType.MapType<?, ?, ?>) fieldType, entities, source);
		} else if (fieldType instanceof FieldType.MultiMapType) {
			return (F) parseMultiMap(text, (FieldType.MultiMapType<?, ?, ?>) fieldType, entities, source);
		} else
			throw new IllegalStateException("Requested initial value parsing for unhandled type " + fieldType + " @" + source);
	}

	private static final char NO_TERMINAL = (char) 0;

	private static <E, C extends BetterCollection<E>> C parseCollection(CharSequence text, FieldType.CollectionType<E, C> fieldType,
		GenericEntitySet entities, Supplier<FilePosition> source) throws MigrationException {
		C collection = fieldType.createEmptyStructure();
		StringBuilder valueStr = new StringBuilder();
		for (int c = 0; c < text.length(); c++) {
			c = MigrationUtil.parseComponentValue(text, c, fieldType.componentType, entities, source, ',', NO_TERMINAL, valueStr,
				collection::add);
		}
		return collection;
	}

	private static <F> int parseComponentValue(CharSequence text, int start, FieldType<F> type, GenericEntitySet entities,
		Supplier<FilePosition> source, char terminal1, char terminal2, StringBuilder valueStr, Consumer<? super F> action)
			throws MigrationException {
		for (start++; start < text.length(); start++) {
			char ch = text.charAt(start);
			if (ch == terminal1 || ch == terminal2) {
				if (start < text.length() - 1 && text.charAt(start) == ch) { // Escaped terminal
					valueStr.append(ch);
					start++;
				} else { // End of value
					action.accept(parseFieldValue(valueStr, type, entities, source));
					valueStr.setLength(0);
					return start;
				}
			} else
				valueStr.append(ch);
		}
		action.accept(parseFieldValue(valueStr, type, entities, source));
		valueStr.setLength(0);
		return start;
	}

	private static <K, V, M extends BetterMap<K, V>> M parseMap(CharSequence text, FieldType.MapType<K, V, M> fieldType,
		GenericEntitySet entities, Supplier<FilePosition> source) throws MigrationException {
		M map = fieldType.createEmptyStructure();
		StringBuilder valueStr = new StringBuilder();
		K[] key = (K[]) new Object[1]; // Obviously not safe, but we don't know anything about K so this will work and make our life easier
		for (int c = 0; c < text.length(); c++) {
			c = parseComponentValue(text, c, fieldType.keyType, entities, source, '=', NO_TERMINAL, valueStr, k -> key[0] = k);
			if (c == text.length())
				throw new MigrationException("No '=' found for entry", source.get());
			c = parseComponentValue(text, c + 1, fieldType.valueType, entities, source, ',', NO_TERMINAL, valueStr,
				v -> map.put(key[0], v));
		}
		return map;
	}

	private static <K, V, M extends BetterMultiMap<K, V>> M parseMultiMap(CharSequence text, FieldType.MultiMapType<K, V, M> fieldType,
		GenericEntitySet entities, Supplier<FilePosition> source) throws MigrationException {
		M map = fieldType.createEmptyStructure();
		StringBuilder valueStr = new StringBuilder();
		Collection<V>[] valueColl = new Collection[1];
		for (int c = 0; c < text.length(); c++) {
			c = parseComponentValue(text, c, fieldType.keyType, entities, source, '=', NO_TERMINAL, valueStr,
				k -> valueColl[0] = map.get(k));
			if (c == text.length())
				throw new MigrationException("No '=' found for entry", source.get());
			do {
				c = parseComponentValue(text, c + 1, fieldType.valueType, entities, source, ',', ';', valueStr, valueColl[0]::add);
			} while (c < text.length() && text.charAt(c) == ',');
		}
		return map;
	}

	public static void printFieldValue(StringBuilder str, FieldType<?> type, Object value) {
		if (value == null)
			str.append("null");
		else if (type instanceof FieldType.SimpleType)
			((FieldType.SimpleType<Object>) type).print(str, value);
		else if (type instanceof EnumType)
			str.append(value);
		else if (type instanceof EntityType)
			printEntityId(str, (GenericEntity) type);
		else if (type instanceof FieldType.CollectionType) {
			printCollection(str, (FieldType.CollectionType<?, ?>) type, (Collection<?>) value);
		} else if (type instanceof FieldType.MapType) {
			printMap(str, (FieldType.MapType<?, ?, ?>) type, (Map<?, ?>) value);
		} else if (type instanceof FieldType.MultiMapType) {
			printMultiMap(str, (FieldType.MultiMapType<?, ?, ?>) type, (MultiMap<?, ?>) value);
		} else
			throw new IllegalStateException("Requested value printing for unhandled type " + type);
	}

	public static void printEntityId(StringBuilder str, GenericEntity entity) {
		boolean first = true;
		for (EntityField<?> field : entity.getType().getIdFields()) {
			if (first)
				first = false;
			else
				str.append(',');
			printFieldValue(str, field.getType(), entity.get(field));
		}
	}

	public static void printCollection(StringBuilder str, FieldType.CollectionType<?, ?> type, Collection<?> value) {
		boolean first = true;
		for (Object v : value) {
			if (first)
				first = false;
			else
				str.append(',');
			int preLen = str.length();
			printFieldValue(str, type.componentType, v);
			escape(str, preLen, ',', ",,");
		}
	}

	public static void printMap(StringBuilder str, FieldType.MapType<?, ?, ?> type, Map<?, ?> value) {
		boolean first = true;
		for (Map.Entry<?, ?> entry : value.entrySet()) {
			if (first)
				first = false;
			else
				str.append(',');
			int preLen = str.length();
			printFieldValue(str, type.keyType, entry.getKey());
			escape(str, preLen, '=', "==");
			str.append('=');
			preLen = str.length();
			printFieldValue(str, type.valueType, entry.getValue());
			escape(str, preLen, ',', ",,");
		}
	}

	public static void printMultiMap(StringBuilder str, FieldType.MultiMapType<?, ?, ?> type, MultiMap<?, ?> value) {
		boolean first = true;
		for (MultiMap.MultiEntry<?, ?> entry : value.entrySet()) {
			if (first)
				first = false;
			else
				str.append(';');
			int preLen = str.length();
			printFieldValue(str, type.keyType, entry.getKey());
			escape(str, preLen, '=', "==");
			str.append('=');
			boolean firstV = true;
			for (Object v : entry.getValues()) {
				if (firstV)
					firstV = false;
				else
					str.append(',');
				preLen = str.length();
				printFieldValue(str, type.valueType, v);
				escape(str, preLen, ',', ",,");
				escape(str, preLen, ';', ";;");
			}
		}
	}

	private static void escape(StringBuilder str, int start, char ch, String replacement) {
		for (int c = start; c < str.length(); c++) {
			if (str.charAt(c) == ch) {
				str.setCharAt(c, replacement.charAt(0));
				str.insert(c + 1, replacement, 1, replacement.length());
				c += replacement.length() - 1;
			}
		}
	}

	public static boolean isIncrementable(FieldType<?> type) {
		if (type == FieldType.SimpleType.LONG) {
			return true;
		} else if (type == FieldType.SimpleType.STRING) {
			return true;// Kinda weird, but we can manage.
		} else if (type == FieldType.SimpleType.INT) {
			return true;// Not perfect since there's the possibility of wraparound, but we'll allow it.
		} else
			return false;
	}

	public static <F> F getInitialValue(FieldType<F> type) {
		if (type == FieldType.SimpleType.LONG) {
			return (F) Long.valueOf(0);
		} else if (type == FieldType.SimpleType.STRING) {
			return (F) "0";
		} else if (type == FieldType.SimpleType.INT) {
			return (F) Integer.valueOf(0);
		} else
			throw new IllegalStateException("Field type " + type + " is not incrementable");
	}

	public static Object adjust(FieldType<?> type, Object value, boolean increment) {
		if (type == FieldType.SimpleType.LONG) {
			if (value == null)
				return Long.valueOf(increment ? 1L : -1L);
			else {
				long current = ((Long) value).longValue();
				if (increment && current != Long.MAX_VALUE)
					return Long.valueOf(current + 1);
				else if (!increment && current != Long.MIN_VALUE)
					return Long.valueOf(current - 1);
				else
					return null;
			}
		} else if (type == FieldType.SimpleType.STRING) {
			if (value == null)
				return "1";
			String str = (String) value;
			char[] chars = new char[str.length() + 1];
			str.getChars(0, str.length(), chars, 1);
			for (int c = str.length(); c > 0; c--) {
				char ch = chars[c];
				if (ch == '9')
					chars[c] = '0';
				else if (increment && ch >= '0' && ch < '9') {
					chars[c] = (char) (ch + 1);
					return new String(chars, 1, str.length());
				} else if (!increment && ch > '0' && ch <= '9') {
					chars[c] = (char) (ch - 1);
					return new String(chars, 1, str.length());
				} else if (increment) { // Prepended non-numeric text.
					// Move it back and add a digit
					System.arraycopy(chars, 1, chars, 0, c - 1);
					chars[c] = '1';
					return new String(chars);
				} else
					return null;
			}
			// The entire text is '9's
			chars[0] = '1';
			return new String(chars);
		} else if (type == FieldType.SimpleType.INT) {
			if (value == null)
				return Integer.valueOf(increment ? 1 : -1);
			else {
				int current = ((Integer) value).intValue();
				if (increment && current != Integer.MAX_VALUE)
					return Integer.valueOf(current + 1);
				else if (!increment && current != Integer.MIN_VALUE)
					return Integer.valueOf(current - 1);
				else
					return null;
			}
		} else
			throw new IllegalStateException("Field type " + type + " is not incrementable");
	}
}
