package org.qommons.data.migration;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import org.qommons.IterableUtils;
import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.StringUtils.ByteIterator;
import org.qommons.collect.BetterCollection;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterMap;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSet;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.MultiEntryHandle;
import org.qommons.collect.MultiMap;
import org.qommons.config.QonfigApp;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigParseException;
import org.qommons.data.impl.MigratableDataSet;
import org.qommons.data.migration.SchemaMigration.AddEntityMigration;
import org.qommons.data.migration.SchemaMigration.AddEnumMigration;
import org.qommons.data.types.Blob;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.FieldType;
import org.qommons.data.types.FieldType.SimpleType;
import org.qommons.data.types.TupleFieldValue;
import org.qommons.data.types.modifiable.ModifiableEntityType;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.ex.ExFunction;
import org.qommons.io.BetterFile;
import org.qommons.io.CsvParser;
import org.qommons.io.LocatedFilePosition;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.io.PositionedContent;
import org.qommons.io.TextParseException;
import org.qommons.io.UnfailingOutputStream;
import org.qommons.io.XmlSerialWriter;

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
		if (leftType == rightType)
			return true;
		else if (leftType.getClass() != rightType.getClass())
			return false;
		else if (leftType instanceof FieldType.SimpleType || leftType == FieldType.BLOB)
			return false;
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

	public static void applyMigrationSet(MigratableDataSet dataSet, MigrationSet migrationSet,
		BetterSortedSet<? extends MigrationSetDef> applied) throws IOException, TextParseException, DataSetModificationException {
		MigrationSession session = new MigrationSession(applied);
		for (Migration migration : migrationSet.getMigrations()) {
			migration.apply(dataSet, session);
		}
		dataSet.migrationApplied(migrationSet.toDef());
	}

	public static FieldType parseFieldType(LocatedPositionedContent text, EntityTypeSet types, CharSequence creatingEntity,
		ExFunction<String, ModifiableEntityType, QonfigInterpretationException> uncreated) throws QonfigInterpretationException {
		int paramIdx = text.indexOf('<');
		if (paramIdx < 0)
			paramIdx = text.indexOf('{');
		if (paramIdx >= 0) {
			List<FieldType<?>> params = new ArrayList<>();
			int closeChar = text.charAt(paramIdx) + 2; // Weird, but this happens to work
			if (text.charAt(text.length() - 1) != closeChar)
				throw new QonfigInterpretationException("Terminating '" + closeChar + "' expected", text);
			LocatedPositionedContent rawType = text.subSequence(0, paramIdx);
			LocatedPositionedContent paramsText = text.subSequence(paramIdx + 1, text.length() - 1);
			int paramCount = PositionedContent.split(paramsText, ',', paramText -> {
				FieldType<?> param = parseFieldType(paramText, types, creatingEntity, uncreated);
				params.add(param);
			});
			int expectedParamCount;
			boolean tuple = false, distinct = false, sorted = false, multiValue = false;
			switch (rawType.toString()) {
			case "":
				tuple = true;
				expectedParamCount = paramCount; // Can tolerate any number of parameter types
				break;
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
				throw new QonfigInterpretationException("Unrecognized raw type '" + rawType + "'", rawType);
			}
			if (paramCount != expectedParamCount)
				throw new QonfigInterpretationException(
					"Expected " + expectedParamCount + " parameters for raw type " + rawType + ", but encountered " + paramCount, text);
			else if (tuple)
				return new FieldType.TupleType(params.toArray(new FieldType[params.size()]));
			else if (expectedParamCount == 1)
				return new FieldType.CollectionType<>(params.get(0), sorted, distinct);
			else if (multiValue)
				return new FieldType.MultiMapType<>(params.get(0), params.get(1), sorted);
			else
				return new FieldType.MapType<>(params.get(0), params.get(1), sorted);
		}
		String textStr = text.toString();
		switch (textStr) {
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
		case "blob":
			return FieldType.BLOB;
		}
		if (text.equals(creatingEntity))
			return FieldType.SELF;
		EntityType entity = types.getEntityType(textStr);
		if (entity != null)
			return entity;
		EnumType enumType = types.getEnumType(textStr);
		if (enumType != null)
			return enumType;
		if (uncreated != null) {
			entity = uncreated.apply(textStr);
			if (entity != null)
				return entity;
		}
		throw new QonfigInterpretationException("Unrecognized type '" + text + "'", text);
	}

	public static <F> F parseFieldValue(CharSequence text, FieldType<F> fieldType, GenericEntitySet entities,
		IntFunction<LocatedFilePosition> source) throws QonfigInterpretationException {
		if ("null".equals(text))
			return null;
		else if (fieldType instanceof EnumType) {
			EnumType enumType = (EnumType) fieldType;
			EnumValue value = enumType.getValue(text.toString());
			if (value == null)
				throw new QonfigInterpretationException("No such enum value " + enumType + "." + text, source.apply(0), text.length());
			return (F) value;
		} else if (fieldType instanceof FieldType.SimpleType) {
			return ((FieldType.SimpleType<F>) fieldType).parse(text.toString(), source);
		} else if (fieldType instanceof EntityType) {
			return (F) parseEntity((EntityType) fieldType, text, entities, source);
		} else if (fieldType == FieldType.BLOB) {
			Blob.InMemoryBlob blob = new Blob.InMemoryBlob();
			try (UnfailingOutputStream out = blob.write()) {
				for (int c = 0; c < text.length(); c++) {
					int hexDigit;
					try {
						hexDigit = StringUtils.hexDigit(text.charAt(c));
					} catch (IllegalArgumentException e) {
						throw new QonfigInterpretationException(e.getMessage(), source.apply(c), 1);
					}
					c++;
					if (c == text.length())
						throw new QonfigInterpretationException("An even number of hex characters was expected", source.apply(c), 0);
					int hexByte = hexDigit << 4;
					try {
						hexDigit = StringUtils.hexDigit(text.charAt(c));
					} catch (IllegalArgumentException e) {
						throw new QonfigInterpretationException(e.getMessage(), source.apply(c), 1);
					}
					hexByte |= hexDigit;
					out.write(hexByte);
				}
			}
			return (F) blob;
		} else if (fieldType instanceof FieldType.TupleType) {
			return (F) parseTuple(text, (FieldType.TupleType) fieldType, entities, source);
		} else if (fieldType instanceof FieldType.CollectionType) {
			return (F) parseCollection(text, (FieldType.CollectionType<?, ?>) fieldType, entities, source);
		} else if (fieldType instanceof FieldType.MapType) {
			return (F) parseMap(text, (FieldType.MapType<?, ?, ?>) fieldType, entities, source);
		} else if (fieldType instanceof FieldType.MultiMapType) {
			return (F) parseMultiMap(text, (FieldType.MultiMapType<?, ?, ?>) fieldType, entities, source);
		} else
			throw new IllegalStateException("Requested initial value parsing for unhandled type " + fieldType + " @" + source.apply(0));
	}

	private static final Pattern DOUBLE_COMMA = Pattern.compile(",,");

	private static GenericEntity parseEntity(EntityType type, CharSequence text, GenericEntitySet entities,
		IntFunction<LocatedFilePosition> source) throws QonfigInterpretationException {
		Object[] id = new Object[type.getIdFields().size()];
		int i = 0;
		int pos = 0, line = 0, col = 0;
		for (EntityField<?> field : type.getIdFields()) {
			CsvParser.ParsedCsvValue value;
			try {
				value = CsvParser.fromCsv(text, pos, line, col, ',');
			} catch (TextParseException e) {
				throw new QonfigInterpretationException(e.getMessage(), source.apply(e.getErrorOffset()), 0, e);
			}
			int fPos = pos;
			id[i++] = parseFieldValue(value.parsed, field.getType(), entities, p -> source.apply(fPos + p));
			pos = value.sourceEnd + 1; // Skip the delimiter
			line = value.endLine;
			col = value.endColumn;
		}
		if (entities == null)
			return null; // Apparently just validating
		GenericEntity found;
		try {
			found = entities.getEntity(type.getName(), id);
		} catch (IOException e) {
			throw new QonfigInterpretationException("Unable to retrieve " + type.getName() + " with ID " + text.subSequence(0, pos),
				source.apply(0), pos, e);
		}
		if (found == null)
			throw new QonfigInterpretationException("No such " + type.getName() + " with ID " + text.subSequence(0, pos), source.apply(0),
				pos);
		return found;
	}

	private static TupleFieldValue parseTuple(CharSequence text, FieldType.TupleType fieldType, GenericEntitySet entities,
		IntFunction<LocatedFilePosition> source) throws QonfigInterpretationException {
		if (text.length() == 0) {
			return null; // Empty text means a null tuple
		}
		if (text.charAt(0) != '{' || text.charAt(text.length() - 1) != '}')
			throw new QonfigInterpretationException("Tuple values must be enclosed by '{' '}'", source.apply(0), 0);
		TupleFieldValue tuple = fieldType.createEmptyStructure();
		int index = 0;
		int start = 1, line = 0, col = 0;
		while (start < text.length() - 1) {
			if (index == fieldType.length())
				throw new QonfigInterpretationException("This tuple only has " + fieldType.length() + " components", source.apply(start),
					0);
			CsvParser.ParsedCsvValue csvValue;
			try {
				csvValue = CsvParser.fromCsv(text, start, line, col, ',');
			} catch (TextParseException e) {
				throw new QonfigInterpretationException(e.getMessage(), source.apply(e.getErrorOffset()), 0, e);
			}
			int fPos = start;
			tuple.set(index, parseFieldValue(csvValue.parsed, fieldType.getComponent(index), entities, p -> source.apply(fPos + p)));
			start = csvValue.sourceEnd + 1; // Skip the delimiter
			line = csvValue.endLine;
			col = csvValue.endColumn + 1;
			index++;
		}
		return tuple;
	}

	private static <E, C extends BetterCollection<E>> C parseCollection(CharSequence text, FieldType.CollectionType<E, C> fieldType,
		GenericEntitySet entities, IntFunction<LocatedFilePosition> source) throws QonfigInterpretationException {
		C collection = fieldType.createEmptyStructure();
		int start = 0, line = 0, col = 0;
		while (start < text.length()) {
			CsvParser.ParsedCsvValue csvValue;
			try {
				csvValue = CsvParser.fromCsv(text, start, line, col, ',');
			} catch (TextParseException e) {
				throw new QonfigInterpretationException(e.getMessage(), source.apply(e.getErrorOffset()), 0, e);
			}
			int fPos = start;
			collection.add(parseFieldValue(csvValue.parsed, fieldType.componentType, entities, p -> source.apply(fPos + p)));
			start = csvValue.sourceEnd + 1; // Skip the delimiter
			line = csvValue.endLine;
			col = csvValue.endColumn + 1;
		}
		return collection;
	}

	private static <K, V, M extends BetterMap<K, V>> M parseMap(CharSequence text, FieldType.MapType<K, V, M> fieldType,
		GenericEntitySet entities, IntFunction<LocatedFilePosition> source) throws QonfigInterpretationException {
		M map = fieldType.createEmptyStructure();
		int start = 0, line = 0, col = 0;
		while (start < text.length()) {
			CsvParser.ParsedCsvValue csvValue;
			try {
				csvValue = CsvParser.fromCsv(text, start, line, col, '=');
			} catch (TextParseException e) {
				throw new QonfigInterpretationException(e.getMessage(), source.apply(e.getErrorOffset()), 0, e);
			}
			int keyPos = start;
			K key = parseFieldValue(csvValue.parsed, fieldType.keyType, entities, p -> source.apply(keyPos + p));
			start = csvValue.sourceEnd + 1; // Skip the delimiter
			line = csvValue.endLine;
			col = csvValue.endColumn + 1;

			try {
				csvValue = CsvParser.fromCsv(text, start, line, col, ',');
			} catch (TextParseException e) {
				throw new QonfigInterpretationException(e.getMessage(), source.apply(e.getErrorOffset()), 0, e);
			}
			int valuePos = start;
			V value = parseFieldValue(csvValue.parsed, fieldType.valueType, entities, p -> source.apply(valuePos + p));
			start = csvValue.sourceEnd + 1; // Skip the delimiter
			line = csvValue.endLine;
			col = csvValue.endColumn + 1;
			map.put(key, value);
		}
		return map;
	}

	private static <K, V, M extends BetterMultiMap<K, V>> M parseMultiMap(CharSequence text, FieldType.MultiMapType<K, V, M> fieldType,
		GenericEntitySet entities, IntFunction<LocatedFilePosition> source) throws QonfigInterpretationException {
		M map = fieldType.createEmptyStructure();
		int start = 0, line = 0, col = 0;
		while (start < text.length()) {
			CsvParser.ParsedCsvValue csvValue;
			try {
				csvValue = CsvParser.fromCsv(text, start, line, col, '=');
			} catch (TextParseException e) {
				throw new QonfigInterpretationException(e.getMessage(), source.apply(e.getErrorOffset()), 0, e);
			}
			int keyPos = start;
			K key = parseFieldValue(csvValue.parsed, fieldType.keyType, entities, p -> source.apply(keyPos + p));
			start = csvValue.sourceEnd + 1; // Skip the delimiter
			line = csvValue.endLine;
			col = csvValue.endColumn + 1;

			MultiEntryHandle<K, V> entry = map.getEntry(key);
			while (start < text.length() && text.charAt(start) != ';') {
				try {
					csvValue = CsvParser.fromCsv(text, start, line, col, ',', ';');
				} catch (TextParseException e) {
					throw new QonfigInterpretationException(e.getMessage(), source.apply(e.getErrorOffset()), 0, e);
				}
				int valuePos = start;
				V value = parseFieldValue(csvValue.parsed, fieldType.valueType, entities, p -> source.apply(valuePos + p));
				start = csvValue.sourceEnd + 1; // Skip the delimiter
				line = csvValue.endLine;
				col = csvValue.endColumn + 1;
				if (entry == null)
					entry = map.getOrPutEntry(key, __ -> Collections.singleton(value), null, null, false, null, null);
				else
					entry.getValues().add(value);
			}
		}
		return map;
	}

	public static void printFieldValue(StringBuilder str, FieldType<?> type, Object value) {
		if (value == null)
			str.append("null");
		else if (type instanceof FieldType.SimpleType)
			((FieldType.SimpleType<Object>) type).print(str, value);
		else if (type instanceof EnumType)
			str.append(((EnumValue) value).getName());
		else if (type instanceof EntityType)
			printEntityId(str, (GenericEntity) value);
		else if (type == FieldType.BLOB) {
			try (InputStream in = ((Blob) value).read()) {
				StringUtils.encodeHex().format(ByteIterator.of(in), new StringUtils.AppendableWriter<>(str), null);
			} catch (IOException e) {
				throw new IllegalStateException("Could not print BLOB data", e);
			}
		} else if (type instanceof FieldType.TupleType) {
			printTuple(str, (FieldType.TupleType) type, (TupleFieldValue) value);
		} else if (type instanceof FieldType.CollectionType) {
			printCollection(str, (FieldType.CollectionType<?, ?>) type, (Collection<?>) value);
		} else if (type instanceof FieldType.MapType) {
			printMap(str, (FieldType.MapType<?, ?, ?>) type, (Map<?, ?>) value);
		} else if (type instanceof FieldType.MultiMapType) {
			printMultiMap(str, (FieldType.MultiMapType<?, ?, ?>) type, (MultiMap<?, ?>) value);
		} else
			throw new IllegalStateException("Requested value printing for unhandled type " + type);
	}

	public static StringBuilder printEntityId(StringBuilder str, GenericEntity entity) {
		if (str == null)
			str = new StringBuilder();
		boolean first = true;
		for (EntityField<?> field : entity.getType().getIdFields()) {
			if (first)
				first = false;
			else
				str.append(',');
			int preLen = str.length();
			printFieldValue(str, field.getType(), entity.get(field));
			CsvParser.escapeCsv(str, preLen, str.length(), ',');
		}
		return str;
	}

	public static void printTuple(StringBuilder str, FieldType.TupleType type, TupleFieldValue value) {
		if (value == null) {
			return; // Null tuple persisted as empty text
		}
		str.append('{'); // This is needed to distinguish a non-null tuple
		for (int c = 0; c < type.length(); c++) {
			if (c != 0)
				str.append(',');
			int preLen = str.length();
			printFieldValue(str, type.getComponent(c), value.get(c));
			CsvParser.escapeCsv(str, preLen, str.length(), ',');
		}
		str.append('}');
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
			CsvParser.escapeCsv(str, preLen, str.length(), ',');
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
			CsvParser.escapeCsv(str, preLen, str.length(), '=');
			str.append('=');
			preLen = str.length();
			printFieldValue(str, type.valueType, entry.getValue());
			CsvParser.escapeCsv(str, preLen, str.length(), ',');
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
			CsvParser.escapeCsv(str, preLen, str.length(), '=');
			str.append('=');
			boolean firstV = true;
			for (Object v : entry.getValues()) {
				if (firstV)
					firstV = false;
				else
					str.append(',');
				preLen = str.length();
				printFieldValue(str, type.valueType, v);
				CsvParser.escapeCsv(str, preLen, str.length(), ',', ';');
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

	public static void writeSchema(EntityTypeSet dataTypes, BetterFile schemaFile) throws IOException {
		try (Writer out = new BufferedWriter(new OutputStreamWriter(schemaFile.write(), StandardCharsets.UTF_8))) {
			XmlSerialWriter.createDocument(out).setEncoding("UTF-8").writeRoot("entity-schema", root -> {
				root.addAttribute("xmlns:core", QDMigrationCore.CORE);
				// Enums first
				for (EnumType enumType : dataTypes.getEnumTypes()) {
					root.addChild("enum", enumXml -> {
						enumXml.addAttribute("enum", enumType.getName());
						for (EnumValue value : enumType.getValues())
							enumXml.addChild("value", valueXml -> valueXml.addAttribute("value", value.getName()));
					});
				}

				// Then entity types
				for (EntityType entityType : dataTypes.getEntityTypes()) {
					root.addChild("entity", entityXml -> {
						entityXml.addAttribute("entity", entityType.getName());
						if (entityType.getSuperTypes().isEmpty())
							entityXml.addAttribute("id", StringUtils.print(",", entityType.getIdFields(), Named::getName).toString());
						else
							entityXml.addAttribute("super",
								String.join(",", IterableUtils.map(entityType.getSuperTypes(), e -> e.getName())));
						for (EntityField<?> field : entityType.getLocalFields()) {
							entityXml.addChild("field", fieldXml -> {
								fieldXml//
								.addAttribute("field", field.getName())//
								.addAttribute("type", field.getType().toString());
								if (field.getMapping() != null) {
									fieldXml.addChild("mapped", mappingXml -> {
										mappingXml.addAttribute("by", field.getMapping().mappedReferenceField.getName());
										if (field.getMapping().keyField != null)
											mappingXml.addAttribute("key", field.getMapping().keyField.getName());
										if (field.getMapping().indexField != null)
											mappingXml.addAttribute("index", field.getMapping().indexField.getName());
										if (field.getMapping().sortByField != null)
											mappingXml.addAttribute("sort-by", field.getMapping().sortByField.getName());
									});
								}
							});
						}
					});
				}
			});
		}
	}

	private static class ParsingEntityType {
		final SchemaMigration.AddEntityMigration add;
		boolean parsed;

		ParsingEntityType(AddEntityMigration add) {
			this.add = add;
		}
	}

	public static ModifiableEntityTypeSet readSchema(BetterFile schemaFile) throws IOException, TextParseException {
		EntitySchema schema;
		try {
			schema = QonfigApp.build()//
				.withToolkit(QDMigrationCore.CORE_MIGRATIONS.get())//
				.withInterpretation(new QDMigrationCore())//
				.build(schemaFile.toUrl().toString(), schemaFile.getName())//
				.interpretApp(EntitySchema.class);
		} catch (QonfigParseException e) {
			throw new TextParseException(e.getMessage(), e.getIssues().get(0).fileLocation);
		}

		ModifiableEntityTypeSet typeSet = new ModifiableEntityTypeSet();
		// Enums first because they're easy
		for (AddEnumMigration migration : schema.getEnums()) {
			migration.applySchemaChange(typeSet);
		}

		// Now entities
		Map<String, ParsingEntityType> entityTypes = new HashMap<>();
		for (AddEntityMigration migration : schema.getEntities()) {
			entityTypes.put(migration.entityName.toString(), new ParsingEntityType(migration));
		}
		BetterSet<String> path = BetterHashSet.create();
		for (ParsingEntityType type : entityTypes.values()) {
			if (!type.parsed)
				createEntityType(type, typeSet, entityTypes, path);
		}
		// Now add non-mapped fields (mapped fields refer to other fields, which may not themselves have been added yet)
		for (ModifiableEntityType type : typeSet.getEntityTypes()) {
			ParsingEntityType add = entityTypes.get(type.getName());
			for (SchemaMigration.AddFieldMigration field : add.add.fields.values()) {
				if (!add.add.idFieldNames.contains(field.fieldName) && field.mapping == null)
					field.applySchemaChange(typeSet);
			}
		}
		// Now add mapped fields
		for (ModifiableEntityType type : typeSet.getEntityTypes()) {
			ParsingEntityType add = entityTypes.get(type.getName());
			for (SchemaMigration.AddFieldMigration field : add.add.fields.values()) {
				if (!add.add.idFieldNames.contains(field.fieldName) && field.mapping != null)
					field.applySchemaChange(typeSet);
			}
		}
		return typeSet;
	}

	private static ModifiableEntityType createEntityType(ParsingEntityType add, ModifiableEntityTypeSet typeSet,
		Map<String, ParsingEntityType> parsing, BetterSet<String> path) throws QonfigInterpretationException {
		CollectionElement<String> pathAdded = path.addElement(add.add.entityName.toString(), null, null, false);
		if (pathAdded == null)
			throw new QonfigInterpretationException("Entity ID cycle: " + path, add.add.getPosition());
		try {
			return add.add.createEntityType(typeSet, str -> {
				ParsingEntityType p = parsing.get(str);
				if (p == null)
					return null;
				else
					return createEntityType(p, typeSet, parsing, path);
			});
		} finally {
			add.parsed = true;
			path.mutableElement(pathAdded.getElementId()).remove();
		}
	}
}
