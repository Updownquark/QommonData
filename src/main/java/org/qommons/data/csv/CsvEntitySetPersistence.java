package org.qommons.data.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.qommons.StringUtils;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
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
	public boolean mayBePersistedData(BetterFile file) throws IOException, TextParseException {
		return StringUtils.endsWithIgnoreCase(file.getName(), CSV_SUFFIX);
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
	public void persist(GenericEntitySet dataSet, BetterFile destDataDir, Predicate<? super EntityType> excludeEntities)
		throws IOException, TextParseException {
		StringBuilder entry = new StringBuilder();
		for (EntityType entityType : dataSet.getTypes().getEntityTypes()) {
			if (excludeEntities != null && excludeEntities.test(entityType))
				continue;
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
					if (!field.isId()) {
						out.write(',');
						out.write(field.getName());
						fieldOrder[headerIdx] = field;
						headerIdx++;
					}
				}
				out.write('\n');

				for (GenericEntity entity : dataSet.getEntities(entityType.getName())) {
					boolean firstEntry = true;
					for (EntityField<?> field : fieldOrder) {
						if (firstEntry)
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
				if (field.getType() instanceof EntityType//
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
			int[] idIndexes = new int[foundIds];
			EntityField<?>[] fieldOrder = new EntityField[line.length];
			Arrays.fill(fieldOrder, -1);
			for (int i = 0; i < line.length; i++) {
				EntityField<?> field = entityType.getField(line[i]);
				if (field != null) {
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
				} else
					System.err.println("Field " + entityType + "." + line[i] + " in header does not exist. This column will be ignored.");
			}
			if (foundFields.size() != entityType.getFields().size()) {
				if (foundIds < entityType.getIdFields().size())
					throw new TextParseException("One or more ID fields of entity " + entityType + " are missing in value persistence",
						new LocatedFilePosition(entityFile.getPath(), new FilePosition(0, 0, 0)));
				else
					System.err.println(
						"One or more fields of entity " + entityType + " are missing in value persistence. These fields will be null.");
			}
			parseValues(parser, entityType, entitySet, idIndexes, fieldOrder, line, firstRound);
		}
		return true;
	}

	private static void parseValues(TabularFileParser parser, EntityType entityType, GenericEntitySet entitySet, int[] idIndexes,
		EntityField<?>[] fieldOrder, String[] line, boolean firstRound) throws IOException, TextParseException {
		Object[] idValues = new Object[idIndexes.length];
		while (parser.parseNextLine(line)) {
			for (int i = 0; i < idIndexes.length; i++) {
				int idColumn = idIndexes[i];
				idValues[i] = MigrationUtil.parseFieldValue(line[idColumn], entityType.getIdFields().get(i).getType(), entitySet,
					() -> parser.getColumnPosition(idColumn));
			}
			GenericEntity entity;
			if (firstRound)
				entity = entitySet.createEntity(entityType.getName(), idValues);
			else
				entity = entitySet.getEntity(entityType.getName(), idValues);
			for (int i = 0; i < line.length; i++) {
				int column = i;
				EntityField<?> field = fieldOrder[column];
				if (fieldOrder == null || (!firstRound && entity.get(field) != null)) {
					continue; // Already populated, no need to retrieve it
				}
				entity.set(field,
					MigrationUtil.parseFieldValue(line[i], field.getType(), entitySet, () -> parser.getColumnPosition(column)));
			}
		}
	}
}
