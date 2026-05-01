package org.qommons.data.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import org.qommons.IterableUtils;
import org.qommons.Subscription;
import org.qommons.collect.ListenerList;
import org.qommons.collect.MultiMap;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.types.Blob;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.EntitySetPersistence;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.ex.ExRunnable;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.CheckSumType;
import org.qommons.io.CsvParser;
import org.qommons.io.FilePosition;
import org.qommons.io.FileUtils;
import org.qommons.io.LocatedFilePosition;
import org.qommons.io.TabularFileParser;
import org.qommons.io.TextParseException;

public class CsvEntitySetPersistence implements EntitySetPersistence {
	private static final String CSV_SUFFIX = ".csv";

	private enum EntityTypeLoadStatus {
		Loading, IdLoaded, Loaded;
	}

	@Override
	public boolean mayBePersistedData(BetterFile file, BetterFile persistenceDir, EntityTypeSet typeSet)
		throws IOException, TextParseException {
		BetterFile parent = file.getParent();
		if (parent.equals(persistenceDir)) {
			int dot = file.getName().indexOf('.');
			if (dot < 0)
				return false;
			EntityType entity = typeSet.getEntityType(file.getName().substring(0, dot));
			if (entity == null)
				return false; // All our files start with the name of an entity type followed by a '.'
			else if (file.isFile()) {
				return file.getName().substring(dot + 1).equalsIgnoreCase(CSV_SUFFIX);
			} else {
				EntityField<?> field = entity.getField(file.getName().substring(dot + 1));
				return field != null && field.getType() == FieldType.BLOB && field.getOwner() == entity;
			}
		} else if (parent.getParent().equals(persistenceDir)) { // 1 level deep
			int dot = parent.getName().indexOf('.');
			if (dot < 0)
				return false;
			EntityType entity = typeSet.getEntityType(parent.getName().substring(0, dot));
			EntityField<?> field = entity == null ? null : entity.getField(parent.getName().substring(dot + 1));
			if (field == null || field.getType() != FieldType.BLOB && field.getOwner() == entity)
				return false;
			dot = file.getName().indexOf('.');
			return dot >= 0 && file.getName().substring(dot + 1).equalsIgnoreCase("blob");
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
		List<EntityField<Blob>> blobFields = null;
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
				if (field.getType() == FieldType.BLOB) {
					if (blobFields == null)
						blobFields = new ArrayList<>(5);
					blobFields.add((EntityField<Blob>) field);
				} else if (field.getMapping() == null && !field.isId()) {
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
		if (blobFields != null) {
			Map<EntityField<?>, Map<String, BetterFile>> blobFiles = new HashMap<>();
			for (EntityField<Blob> field : blobFields) {
				BetterFile blobDir = destDataDir.at(field.getOwner().getName() + "." + field.getName());
				Map<String, BetterFile> fieldFiles = new HashMap<>();
				blobFiles.put(field, fieldFiles);
				if (blobDir.isDirectory()) {
					for (BetterFile file : blobDir.listFiles())
						fieldFiles.put(file.getName(), file);
				}
			}
			for (GenericEntity entity : entities) {
				for (EntityField<Blob> field : blobFields) {
					Blob blob = entity.get(field);
					if (isMine(blob, entity, destDataDir, field))
						blobFiles.get(field).remove(idToFileName(MigrationUtil.printEntityId(null, entity).toString()) + ".blob");
					else
						entity.set(field, copyBlob(blob, entity, destDataDir, field));
				}
			}
			for (Map<String, BetterFile> fieldFiles : blobFiles.values()) {
				for (BetterFile blobFile : fieldFiles.values())
					blobFile.delete(null);
			}
		}
	}

	private static boolean isMine(Blob blob, GenericEntity entity, BetterFile destDataDir, EntityField<Blob> field) {
		if (!(blob instanceof FileBlob))
			return false;
		BetterFile file = ((FileBlob) blob).getFile();
		if (!file.getParent().getParent().equals(destDataDir))
			return false;
		String parent = file.getParent().getName();
		int dot = parent.indexOf('.');
		if (dot < 0)
			return false;
		EntityType entityType = entity.getType().getTypeSet().getEntityType(parent.substring(0, dot));
		if (entityType == null || entityType != field.getOwner())
			return false;
		return file.getName().equals(idToFileName(MigrationUtil.printEntityId(null, entity) + ".blob"));
	}

	private static Blob copyBlob(Blob blob, GenericEntity entity, BetterFile destDataDir, EntityField<Blob> field) {
		Blob myBlob = createBlob(entity, destDataDir, field);
		try {
			FileUtils.copy(blob::read, myBlob::write);
		} catch (IOException e) {
			System.err.println("Failed to copy blob data");
			e.printStackTrace();
		}
		return myBlob;
	}

	private static Blob createBlob(GenericEntity entity, BetterFile persistenceDir, EntityField<Blob> field) {
		return new FileBlob(persistenceDir.at(field.getOwner().getName() + "." + field.getName())
			.at(idToFileName(MigrationUtil.printEntityId(null, entity) + ".blob")));
	}

	private static final String[] FILE_NAME_SUBS = new String[128];
	static {
		FILE_NAME_SUBS['/'] = "%SLASH%";
		FILE_NAME_SUBS['\\'] = "%BKSLASH%";
		FILE_NAME_SUBS[':'] = "%COLON%";
		FILE_NAME_SUBS['*'] = "%ASTSK%";
		FILE_NAME_SUBS['?'] = "%QSTN%";
		FILE_NAME_SUBS['"'] = "%QUOT%";
		FILE_NAME_SUBS['<'] = "%LT%";
		FILE_NAME_SUBS['>'] = "%GT%";
		FILE_NAME_SUBS['|'] = "%PIPE%";
	}

	private static String idToFileName(String id) {
		StringBuilder replaced = null;
		for (int c = 0; c < id.length(); c++) {
			char ch = id.charAt(c);
			String sub = FILE_NAME_SUBS[ch];
			if (sub != null) {
				if (replaced == null)
					replaced = new StringBuilder().append(id, 0, c);
				replaced.append(sub);
			} else if (replaced != null)
				replaced.append(ch);
		}
		if (replaced == null)
			return id;
		else
			return replaced.toString();
	}

	@Override
	public void persist(GenericEntitySet dataSet, BetterFile destDataDir) throws IOException {
		for (EntityType entityType : dataSet.getTypes().getEntityTypes()) {
			persistEntity(entityType, IterableUtils.filter(dataSet.getEntities(entityType.getName()), e -> e.getType() == entityType), null,
				destDataDir);
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
		for (EntityField<?> field : type.getFields()) {
			if (field.getType() == FieldType.BLOB && field.getOwner() == type) {
				BetterFile blobDir = dataDir.at(type.getName() + "." + field.getName());
				if (blobDir.exists())
					blobDir.delete(null);
			}
		}
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
		List<EntityField<Blob>> blobFields = Collections.emptyList();
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
		} else {
			loadedTypes.put(entityType, EntityTypeLoadStatus.Loaded);
			for (EntityField<?> field : entityType.getFields()) {
				if (field.getType() == FieldType.BLOB) {
					if (blobFields.isEmpty())
						blobFields = new ArrayList<>(5);
					blobFields.add((EntityField<Blob>) field);
				}
			}
		}
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
					if (field.getMapping() == null && field.getType() != FieldType.BLOB && !foundFields.contains(field.getName())) {
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
			parseValues(parser, entityType, entitySet, idIndexes, fieldOrder, line, firstRound, directory, blobFields);
		}
		return true;
	}

	private static void parseValues(TabularFileParser parser, EntityType entityType, GenericEntitySet entitySet, int[] idIndexes,
		EntityField<?>[] fieldOrder, String[] line, boolean firstRound, BetterFile persistenceDir, List<EntityField<Blob>> blobFields)
			throws IOException, TextParseException {
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
			if (!blobFields.isEmpty()) {
				String id = MigrationUtil.printEntityId(null, entity).toString();
				for (EntityField<Blob> field : blobFields) {
					BetterFile blobFile = persistenceDir.at(field.getOwner().getName() + "." + field.getName())
						.at(idToFileName(id) + ".blob");
					entity.set(field, createBlob(entity, persistenceDir, field));
				}
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

	static class FileBlob implements Blob {
		private final BetterFile theFile;
		private final ListenerList<ExRunnable<IOException>> theListeners;

		FileBlob(BetterFile file) {
			theFile = file;
			theListeners = ListenerList.build().build();
		}

		BetterFile getFile() {
			return theFile;
		}

		@Override
		public long length() {
			return Math.max(0, theFile.length());
		}

		@Override
		public InputStream read() throws IOException {
			if (theFile.exists())
				return theFile.read();
			else
				return EmptyInputStream.INSTANCE;
		}

		@Override
		public InputStream read(int offset) throws IOException {
			if (theFile.exists())
				return theFile.read(offset, null);
			else if (offset > 0)
				throw new IOException("Offset " + offset + " of 0");
			else
				return EmptyInputStream.INSTANCE;
		}

		@Override
		public OutputStream write() throws IOException {
			if (!theFile.isFile())
				theFile.create(false);
			return new ListenableOutputStream(theFile.write(), this::fireChanged);
		}

		@Override
		public void clear() throws IOException {
			if (theFile.exists()) {
				theFile.delete(null);
				fireChanged();
			}
		}

		@Override
		public Subscription onChange(ExRunnable<IOException> listener) {
			return theListeners.add(listener, false);
		}

		private void fireChanged() {
			theListeners.forEach(l -> {
				try {
					l.run();
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		}

		@Override
		public String toString() {
			return Blob.printHex(this, 100);
		}
	}

	static class EmptyInputStream extends InputStream {
		static final EmptyInputStream INSTANCE = new EmptyInputStream();

		@Override
		public int read() throws IOException {
			return -1;
		}

		@Override
		public int read(byte[] b) throws IOException {
			return -1;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return -1;
		}

		@Override
		public long skip(long n) throws IOException {
			return 0;
		}

		@Override
		public int available() throws IOException {
			return 0;
		}
	}
}
