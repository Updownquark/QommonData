package org.qommons.data.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import org.qommons.StringUtils;
import org.qommons.collect.MultiMap;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.EntitySetPersistence;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.CheckSumType;
import org.qommons.io.CsvParser;
import org.qommons.io.FilePosition;
import org.qommons.io.LocatedFilePosition;
import org.qommons.io.TabularFileParser;
import org.qommons.io.TextParseException;

public class CsvEntitySetPersistence implements EntitySetPersistence {
	private static final String CSV_SUFFIX = ".csv";

	private enum EntityTypeLoadStatus {
		Loading, IdLoaded, Loaded;
	}

	@Override
	public boolean mayBePersistedData(BetterFile file, EntityTypeSet typeSet) throws IOException, TextParseException {
		if (file.isFile() && StringUtils.endsWithIgnoreCase(file.getName(), CSV_SUFFIX)) {
			return typeSet.getEntityType(file.getName().substring(0, file.getName().length() - CSV_SUFFIX.length())) != null;
		} else
			return false;
	}

	@Override
	public void populate(GenericEntitySet entitySet, BetterFile directory) throws IOException, TextParseException {
		Map<EntityType, EntityTypeLoadStatus> loadedTypes = new HashMap<>();
		for (EntityType entityType : entitySet.getTypes().getEntityTypes()) {
			if (!loadEntities(entityType, directory, CSV_SUFFIX, entitySet, loadedTypes, true))
				throw new IllegalStateException("Unexpected identity cycle detected in entity typed");
		}
		for (EntityType entityType : entitySet.getTypes().getEntityTypes()) {
			loadEntities(entityType, directory, CSV_SUFFIX, entitySet, loadedTypes, false);
		}
	}

	@Override
	public void persistEntity(EntityType entityType, Iterable<? extends GenericEntity> entities,
		Predicate<? super GenericEntity> changedTest, BetterFile destDataDir) throws IOException {
		StringBuilder entry = new StringBuilder();
		BetterFile entityFile = destDataDir.at(entityType.getName() + CSV_SUFFIX);
		try (Writer out = new BufferedWriter(new OutputStreamWriter(entityFile.write(), StandardCharsets.UTF_8))) {
			int headerIdx = 0;
			EntityField<?>[] fieldOrder = new EntityField[entityType.getFields().size()];
			// Write ID fields first
			for (EntityField<?> field : entityType.getIdFields()) {
				if (headerIdx > 0)
					out.write(',');
				out.write(field.getName());
				fieldOrder[headerIdx] = field;
				headerIdx++;
			}
			for (EntityField<?> field : entityType.getFields()) {
				// Mapped fields do not need to be persisted, since their content is based on the properties of the target entity
				if (field.getMapping() == null && !field.isId()) {
					out.write(',');
					out.write(field.getName());
					fieldOrder[headerIdx] = field;
					headerIdx++;
				}
			}
			out.write('\n');

			for (GenericEntity entity : entities) {
				// CSV doesn't lend itself to being able to just rewrite certain entities,
				// so we can't use the changed test
				boolean firstEntry = true;
				for (EntityField<?> field : fieldOrder) {
					if (field == null) { // Just means there were mapped fields
						continue;
					} else if (firstEntry)
						firstEntry = false;
					else
						out.write(',');
					MigrationUtil.printFieldValue(entry, field.getType(), entity.get(field));
					out.write(CsvParser.toCsv(entry.toString(), ','));
					entry.setLength(0);
				}
				out.write('\n');
			}
		}
	}

	@Override
	public void persist(GenericEntitySet dataSet, BetterFile destDataDir) throws IOException {
		for (EntityType entityType : dataSet.getTypes().getEntityTypes()) {
			persistEntity(entityType, dataSet.getEntities(entityType.getName()), null, destDataDir);
		}
	}

	@Override
	public String getPersistentEntityHash(BetterFile dataDir, EntityType type) throws IOException {
		BetterFile entityFile = dataDir.at(type.getName() + CSV_SUFFIX);
		if (!entityFile.isFile())
			return null;
		return entityFile.getCheckSum(CheckSumType.SHA256, null);
	}

	@Override
	public void deleteExclusiveEntityContent(BetterFile dataDir, EntityType type) throws IOException {
		BetterFile entityFile = dataDir.at(type.getName() + CSV_SUFFIX);
		if (entityFile.exists())
			entityFile.delete(null);
	}

	private static boolean loadEntities(EntityType entityType, BetterFile directory, String suffix, GenericEntitySet entitySet,
		Map<EntityType, EntityTypeLoadStatus> loadedTypes, boolean firstRound) throws IOException, TextParseException {
		EntityTypeLoadStatus status = loadedTypes.get(entityType);
		if (status != null) {
			switch (status) {
			case Loading:
				return false; // ID reference cycle detected
			case IdLoaded:
				if (firstRound)
					return true;
				break;
			case Loaded:
				return true;
			}
		}
		BetterFile entityFile = directory.at(entityType.getName() + suffix);
		if (!entityFile.exists()) {
			loadedTypes.put(entityType, EntityTypeLoadStatus.Loaded);
			return true;
		}
		if (firstRound) {
			// See if the type has any entity references we need to load first
			loadedTypes.put(entityType, EntityTypeLoadStatus.Loading);
			boolean unresolvedReferences = false;
			for (EntityField<?> field : entityType.getFields()) {
				if (field.getMapping() == null && field.getType() instanceof EntityType//
					&& !loadEntities((EntityType) field.getType(), directory, suffix, entitySet, loadedTypes, firstRound)) {
					if (field.isId()) {// ID reference cycle
						loadedTypes.remove(entityType);
						return false;
					} else
						unresolvedReferences = true;
				}
			}
			loadedTypes.put(entityType, unresolvedReferences ? EntityTypeLoadStatus.IdLoaded : EntityTypeLoadStatus.Loaded);
		} else
			loadedTypes.put(entityType, EntityTypeLoadStatus.Loaded);
		try (TabularFileParser parser = TabularFileParser.parse(entityFile)) {
			String[] line = parser.parseNextLine();
			EntityField<?>[] header = new EntityField[line.length];
			Set<String> foundFields = new HashSet<>();
			int foundIds = 0;
			int[] idIndexes = new int[entityType.getIdFields().size()];
			EntityField<?>[] fieldOrder = new EntityField[line.length];
			for (int i = 0; i < line.length; i++) {
				EntityField<?> field = entityType.getField(line[i]);
				if (field == null) {
					System.err.println("Field " + entityType + "." + line[i] + " in header does not exist. This column will be ignored.");
				} else if (field.getMapping() != null) {
					System.err
					.println("Field " + entityType + "." + line[i] + " in header is a mapped field. This column will be ignored.");
				} else {
					if (foundFields.add(line[i])) {
						if (firstRound && field.getType() instanceof EntityType && !loadedTypes.containsKey(field.getType())) {
							continue; // We don't yet have entities parsed for this type, so skip it for now
						}
						header[i] = field;
						if (field.isId()) {
							foundIds++;
							idIndexes[entityType.getIdFields().indexOf(field)] = i;
						} else if (firstRound || field.getType() instanceof EntityType)
							fieldOrder[i] = field;
					} else
						System.err.println("Field " + field + " is present twice in the header.  The first entry will be used.");
				}
			}
			if (foundFields.size() != entityType.getFields().size()) {
				if (foundIds < entityType.getIdFields().size())
					throw new TextParseException("One or more ID fields of entity " + entityType + " are missing in value persistence",
						new LocatedFilePosition(entityFile.getPath(), new FilePosition(0, 0, 0)));
				StringBuilder msg = null;
				for (EntityField<?> field : entityType.getFields()) {
					if (field.getMapping() == null && !foundFields.contains(field.getName())) {
						if (msg == null)
							msg = new StringBuilder("One or more fields of entity ").append(entityType)
							.append(" are missing in value persistence: ");
						else
							msg.append(", ");
						msg.append(field.getName());
					}
				}
				if (msg != null)
					System.err.println(msg.append(". These fields will be null.").toString());
			}
			parseValues(parser, entityType, entitySet, idIndexes, fieldOrder, line, firstRound);
		}
		return true;
	}

	private static void parseValues(TabularFileParser parser, EntityType entityType, GenericEntitySet entitySet, int[] idIndexes,
		EntityField<?>[] fieldOrder, String[] line, boolean firstRound) throws IOException, TextParseException {
		Object[] idValues = new Object[idIndexes.length];
		ColumnPositionGetter sourcePos = new ColumnPositionGetter(parser);
		while (parser.parseNextLine(line)) {
			for (int i = 0; i < idIndexes.length; i++) {
				int idColumn = idIndexes[i];
				idValues[i] = MigrationUtil.parseFieldValue(line[idColumn], entityType.getIdFields().get(i).getType(), entitySet,
					sourcePos.setColumn(idColumn));
			}
			GenericEntity entity;
			if (firstRound)
				entity = entitySet.createEntity(entityType.getName(), idValues);
			else
				entity = entitySet.getEntity(entityType.getName(), idValues);
			for (int i = 0; i < line.length; i++) {
				int column = i;
				EntityField<?> field = fieldOrder[column];
				if (field == null || (!firstRound && entity.get(field) != null)) {
					continue; // Already populated, no need to retrieve it
				}
				populateField(entity, field, line[i], entitySet, sourcePos.setColumn(column));
			}
		}
	}

	private static <F> void populateField(GenericEntity entity, EntityField<F> field, String text, GenericEntitySet entitySet,
		IntFunction<LocatedFilePosition> source) throws IOException, TextParseException {
		F value = MigrationUtil.parseFieldValue(text, field.getType(), entitySet, source);
		if (field.getType() instanceof FieldType.CollectionType) {
			((Collection<Object>) entity.get(field)).addAll((Collection<?>) value);
		} else if (field.getType() instanceof FieldType.MapType) {
			((Map<Object, Object>) entity.get(field)).putAll((Map<?, ?>) value);
		} else if (field.getType() instanceof FieldType.MultiMapType) {
			((MultiMap<Object, Object>) entity.get(field)).putAll((MultiMap<?, ?>) value);
		} else
			entity.set(field, value);
	}

	static class ColumnPositionGetter implements IntFunction<LocatedFilePosition> {
		private final TabularFileParser theParser;
		private int theColumn;

		ColumnPositionGetter(TabularFileParser parser) {
			theParser = parser;
		}

		ColumnPositionGetter setColumn(int column) {
			theColumn = column;
			return this;
		}

		@Override
		public LocatedFilePosition apply(int p) {
			LocatedFilePosition columnPos = (LocatedFilePosition) theParser.getColumnPosition(theColumn);
			if (p == 0)
				return columnPos;
			return new LocatedFilePosition(columnPos.getFileLocation(), //
				columnPos.getPosition() + p, columnPos.getLineNumber(), columnPos.getCharNumber() + p);
		}
	}
}
