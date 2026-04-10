package org.qommons.data.impl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.qommons.ClassMap.TypeMatch;
import org.qommons.IterableUtils;
import org.qommons.Stamped;
import org.qommons.TimeUtils;
import org.qommons.collect.BetterMultiMap;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.MultiMap;
import org.qommons.data.impl.DataSetMigrationException.MigrationFailureCause;
import org.qommons.data.mapping.EntityFieldMapping;
import org.qommons.data.mapping.EntityTypeMapping;
import org.qommons.data.mapping.EntityTypeSetMapping;
import org.qommons.data.mapping.MappedEntitySet;
import org.qommons.data.migration.Migration;
import org.qommons.data.migration.MigrationException;
import org.qommons.data.migration.MigrationPersistence;
import org.qommons.data.migration.MigrationSet;
import org.qommons.data.migration.MigrationSetDef;
import org.qommons.data.migration.MigrationUtil;
import org.qommons.data.migration.MigrationUtil.MigrationDiff;
import org.qommons.data.migration.SchemaMigration;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.FieldType;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.values.DataSetModificationException;
import org.qommons.data.values.EntitySetPersistence;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;
import org.qommons.ex.ExFunction;
import org.qommons.fn.TriConsumer;
import org.qommons.io.BetterFile;
import org.qommons.io.CsvParser;
import org.qommons.io.FileUtils;
import org.qommons.io.InMemoryFileSystem;
import org.qommons.io.TabularFileParser;
import org.qommons.io.TemporalBackupScheme;
import org.qommons.io.TextParseException;
import org.qommons.threading.QommonsTimer;
import org.qommons.tree.BetterTreeSet;

public class VersionedDataScheme {
	public static final String VERSION_DIR_PATTERN = "YYYYMMDD_HHmmss";
	public static final DateTimeFormatter VERSION_DIR_FORMAT = DateTimeFormatter.ofPattern(VERSION_DIR_PATTERN);
	private static final Duration ONE_SECOND = Duration.ofSeconds(1);
	public static final String MIGRATION_STORE = "Applied Migrations.csv";
	public static final String DATA_SCHEMA = "Entity Schema.xml";

	public static class InitializedDataScheme {
		public final EntityTypeSetMapping mappedEntityTypes;
		public final BetterSortedSet<MigrationSet> migrations;

		public InitializedDataScheme(EntityTypeSetMapping mappedEntityTypes, BetterSortedSet<MigrationSet> migrations) {
			this.mappedEntityTypes = mappedEntityTypes;
			this.migrations = migrations;
		}

		public LoadedGenericData load(BetterFile rootDataDirectory, BetterFile initDataDir, EntitySetPersistence persistence)
			throws IOException, DataSetMigrationException {
			PersistedEntitySet data = parseCurrentData(rootDataDirectory, mappedEntityTypes.getGenericTypes(), migrations, initDataDir,
				persistence);
			return new LoadedGenericData(mappedEntityTypes, data.entityData, data.persistenceDir);
		}
	}

	public static class LoadedGenericData {
		public final EntityTypeSetMapping mappedEntityTypes;
		public final GenericEntitySet entityData;
		public final BetterFile persistenceDir;

		public LoadedGenericData(EntityTypeSetMapping mappedEntityTypes, GenericEntitySet entityData, BetterFile persistenceDir) {
			this.mappedEntityTypes = mappedEntityTypes;
			this.entityData = entityData;
			this.persistenceDir = persistenceDir;
		}

		public LoadedEntityData mapToCode(BiFunction<EntityTypeMapping<?>, GenericEntity, ?> entityCreator,
			TriConsumer<Object, GenericEntity, MappedEntitySet> entityInitializer) throws IOException, TextParseException {
			MappedEntitySet mappedEntities = MappedEntitySet.create(entityData, mappedEntityTypes, entityCreator, entityInitializer);
			return new LoadedEntityData(mappedEntityTypes, mappedEntities, persistenceDir);
		}
	}

	public static class LoadedEntityData {
		public final EntityTypeSetMapping mappedEntityTypes;
		public final MappedEntitySet entityData;
		public final BetterFile persistenceDir;

		public LoadedEntityData(EntityTypeSetMapping mappedEntityTypes, MappedEntitySet entityData, BetterFile persistenceDir) {
			this.mappedEntityTypes = mappedEntityTypes;
			this.entityData = entityData;
			this.persistenceDir = persistenceDir;
		}

		public RollingEntitySetPersistence createPersister(EntitySetPersistence persistence) throws IOException {
			return new RollingEntitySetPersistence(mappedEntityTypes, persistenceDir, persistence, entityData, true);
		}
	}

	public static class RollingEntitySetPersistence {
		private static final Duration PERSISTENCE_DELAY = Duration.ofMillis(200);

		public final EntityTypeSetMapping mappedEntityTypes;
		public final BetterFile persistenceDir;
		private final EntitySetPersistence thePersistence;
		private final Map<EntityTypeMapping<? extends Stamped>, Long> theTypeStamps;
		private final Map<EntityType, String> theTypeFileHashes;
		private volatile long theSaveStamp;
		private Instant theCurrentDataTime;
		private TemporalBackupScheme theBackupScheme;

		public RollingEntitySetPersistence(EntityTypeSetMapping types, BetterFile persistenceDir, EntitySetPersistence persistence,
			MappedEntitySet entityData, boolean optimizeWrites) throws IOException {
			mappedEntityTypes = types;
			this.persistenceDir = persistenceDir;
			thePersistence = persistence;
			// See if we can save time by preserving aggregate stamps for entity types
			Map<EntityTypeMapping<? extends Stamped>, Long> typeStamps = null;
			if (optimizeWrites) {
				for (EntityTypeMapping<?> type : types.getEntityTypes().values()) {
					if (Stamped.class.isAssignableFrom(type.getRealType())) {
						if (typeStamps == null)
							typeStamps = new HashMap<>();
						long stamp;
						if (entityData == null)
							stamp = -1; // We'll persist the first round regardless
						else {
							stamp = getTypeStamp(entityData, (Class<? extends Stamped>) type.getRealType());
						}
						typeStamps.put((EntityTypeMapping<? extends Stamped>) type, stamp);
					}
				}
			}
			theTypeStamps = typeStamps;
			if (optimizeWrites) {
				theTypeFileHashes = new HashMap<>();
				for (EntityType type : types.getGenericTypes().getEntityTypes()) {
					String hash = thePersistence.getPersistentEntityHash(persistenceDir, type);
					if (hash == null)
						hash = "";
					theTypeFileHashes.put(type, hash);
				}
			} else
				theTypeFileHashes = null;
		}

		/**
		 * Creates a new persister writing to a different directory. This is useful e.g. for exporting data to a directory.
		 *
		 * @param newPersistenceDir The directory to write to
		 * @param optimizeWrites Whether to keep information about the data that has been written to the new persister to make repeated
		 *        write operations of the same data faster
		 * @return The new persister
		 * @throws IOException If <code>optimizeWrites</code> is true and inspection of the existing persistent data fails
		 */
		public RollingEntitySetPersistence writeTo(BetterFile newPersistenceDir, boolean optimizeWrites) throws IOException {
			return new RollingEntitySetPersistence(mappedEntityTypes, newPersistenceDir, thePersistence, null, optimizeWrites);
		}

		public RollingEntitySetPersistence withBackup(TemporalBackupScheme backup) {
			theBackupScheme = backup;
			if (backup != null) { // Update the current data time so we back up the main data set when needed
				theCurrentDataTime = Instant.ofEpochMilli(getDataModTime());
			}
			return this;
		}

		private long getDataModTime() {
			long[] maxTime = new long[1];
			forAllDataSetFiles((file, path) -> maxTime[0] = Math.max(maxTime[0], file.getLastModified()));
			return maxTime[0];
		}

		private interface DataSetFileAction<X extends Exception> {
			void forDataSetFile(BetterFile file, CharSequence path) throws X;
		}

		private <X extends Exception> void forAllDataSetFiles(DataSetFileAction<X> forEach) throws X {
			forAllDataSetFiles(persistenceDir, new StringBuilder(), forEach, true);
		}

		private <X extends Exception> void forAllDataSetFiles(BetterFile file, StringBuilder path, DataSetFileAction<X> forEach,
			boolean root) throws X {
			int preLen = path.length();
			if (file.isFile()) {
				if (root && (file.getName().equals(MIGRATION_STORE) || file.getName().equals(DATA_SCHEMA))) {
					path.append(file);
					forEach.forDataSetFile(file, path);
				} else {
					try {
						if (thePersistence.mayBePersistedData(file)) {
							path.append(file);
							forEach.forDataSetFile(file, path);
						}
					} catch (IOException | TextParseException e) {
						// Just keep going
					}
				}
			} else {
				path.append(file.getName()).append('/');
				for (BetterFile sub : file.listFiles())
					forAllDataSetFiles(sub, path, forEach, false);
			}
			path.setLength(preLen);
		}

		public void save(Iterable<?> entities) throws IOException, TextParseException {
			theSaveStamp++; // Stop any unfinished save operations from running, since these are now obsolete
			Predicate<EntityTypeMapping<?>> excludeGenerifyingEntities;
			Predicate<EntityType> excludePersistingEntities;
			if (theTypeStamps != null) {
				MappedEntitySet entitySet = MappedEntitySet.create(entities, mappedEntityTypes);
				Set<EntityType> excluded = new HashSet<>();
				synchronized (theTypeStamps) {
					for (Map.Entry<EntityTypeMapping<? extends Stamped>, Long> stampedType : theTypeStamps.entrySet()) {
						long newStamp = getTypeStamp(entitySet, stampedType.getKey().getRealType());
						if (newStamp == stampedType.getValue().longValue())
							excluded.add(stampedType.getKey().getGenericType());
						else
							stampedType.setValue(newStamp);
					}
				}
				excludeGenerifyingEntities = type -> excluded.contains(type.getGenericType());
				excludePersistingEntities = excluded::contains;
			} else {
				excludeGenerifyingEntities = null;
				excludePersistingEntities = null;
			}
			EntitySetGenerifier generifier = new EntitySetGenerifier(mappedEntityTypes).exclude(excludeGenerifyingEntities);
			generifier.add(entities);
			GenericEntitySet genericEntities = generifier.getEntities();
			long stamp = ++theSaveStamp;
			QommonsTimer.getCommonInstance().offload(() -> persist(genericEntities, stamp, excludePersistingEntities), PERSISTENCE_DELAY);
		}

		private static long getTypeStamp(MappedEntitySet entityData, Class<? extends Stamped> type) {
			return Stamped.compositeStamp(entityData.get(type, false));
		}

		private synchronized void persist(GenericEntitySet entities, long stamp, Predicate<EntityType> excludeEntities) {
			if (stamp != theSaveStamp) {
				return;// Another save operation has happened, so this operation is obsolete
			}
			// Persist the data to memory
			BetterFile memoryPersistence = new InMemoryFileSystem().at("/QommonsPersistence/");
			try {
				thePersistence.persist(entities, memoryPersistence, excludeEntities);
			} catch (IOException e) {
				System.err.println("In-memory files should not throw IOExceptions!");
				e.printStackTrace();
				return;
			} catch (TextParseException e) {
				System.err.println("In-memory entity sets should not throw parse exceptions!");
				e.printStackTrace();
				return;
			}
			if (stamp != theSaveStamp) {
				return;
			}
			Map<EntityType, String> entityDataChanges = null;
			if (theTypeFileHashes != null) {
				try {
					// Check the file hashes to see what's actually changed so we don't persist anything
					for (EntityType entity : mappedEntityTypes.getGenericTypes().getEntityTypes()) {
						String newHash = thePersistence.getPersistentEntityHash(memoryPersistence, entity);
						if (newHash == null) // Not persisted due to exclusion
							continue;
						String prevHash = theTypeFileHashes.get(entity);
						if (prevHash.equals(newHash)) {
							// Persistence for this entity has not changed, don't waste time writing the data
							thePersistence.deleteExclusiveEntityContent(memoryPersistence, entity);
						} else {
							if (entityDataChanges == null)
								entityDataChanges = new HashMap<>();
							entityDataChanges.put(entity, newHash);
						}
					}
				} catch (IOException e) {
				}
				if (stamp != theSaveStamp // Obsolete persistence call
					|| entityDataChanges == null) { // Nothing persistent has actually changed
					return;
				}
			}
			// Now we're ready to persist the new data

			if (theBackupScheme != null) {
				Instant now = Instant.now();
				BackupManager mgr = new BackupManager();
				try {
					if (theBackupScheme.dataRenewed(now, mgr)) {
						// Back up the new data to a new backup folder
						String newBackupName = "BAK_" + VERSION_DIR_FORMAT.format(now);
						BetterFile newBackupDir = persistenceDir.at(newBackupName);
						newBackupDir.create(true);
						backUpTo(newBackupDir);
					}
					if (mgr.backupCurrentData) {
						// Back up the current (now out-of-date) data set to a new backup folder
						String newBackupName = "BAK_" + VERSION_DIR_FORMAT.format(theCurrentDataTime);
						BetterFile newBackupDir = persistenceDir.at(newBackupName);
						newBackupDir.create(true);
						backUpTo(newBackupDir);
					}
				} catch (IOException e) {
					System.err.println("Backup management failed");
					e.printStackTrace();
				}
				theCurrentDataTime = now;
			}

			/* Replace the persisted data. For resiliency, we do this in phases:
			 * 1) Make a backup copy of the files that will be replaced into temporary files labeled with ".OLD".
			 * 2) Write the data that needs to be updated to temporary files labeled with ".NEW". This can be done concurrently with step 1.
			 * 3) One-by-one, delete each file to be replaced and rename the corresponding ".NEW" file to replace it.
			 *
			 * If step 3 succeeds, delete all the ".OLD" backup files.
			 * If step 3 fails, revert to the previous version of the data by replacing all files that may or may not have been replaced
			 * with ".NEW" files already with their ".OLD" backup, then deleting all the ".NEW" files.
			 */
			Set<BetterFile> written = new HashSet<>();
			try {
				writeTempPersistentData(memoryPersistence, persistenceDir, written);
			} catch (IOException | RuntimeException e) {
				System.err.println("Failed phase 1 of persistence sequence");
				e.printStackTrace();
				// Nothing's been changed. We can just delete the temp files we've already written.
				deleteFiles(written, ".OLD");
				deleteFiles(written, ".NEW");
				return;
			}
			try {
				replaceFiles(written, ".NEW");
				// Data replacement succeeded. Clean up and
				theTypeFileHashes.putAll(entityDataChanges); // Update the file hashes after we've written the data
				deleteFiles(written, ".OLD");
			} catch (IOException | RuntimeException e) {
				// Data replacement failed. Revert to the backed up data and make sure the next save() call writes everything it needs to.
				if (theTypeStamps != null) {
					synchronized (theTypeStamps) {
						// The type stamps are modified in the synchronous save() call before this async call.
						// It's not possible to back them up or delay modifying them
						// because there may have been save() calls since the one that made this call.
						// The best we can do is clear it out so all the data will be serialized with the next save call.
						for (Map.Entry<?, Long> typedStamp : theTypeStamps.entrySet())
							typedStamp.setValue(-1L);
					}
				}
				replaceFilesForceAll(written, ".OLD");
				deleteFiles(written, ".NEW");
			}
		}

		private void backUpTo(BetterFile targetDir) throws IOException {
			forAllDataSetFiles((file, path) -> {
				FileUtils.sync().from(file).to(targetDir.at(path.toString())).sync();
			});
		}

		private void writeTempPersistentData(BetterFile source, BetterFile dest, Set<BetterFile> written) throws IOException {
			if (source.isFile()) {
				written.add(dest);
				// Make a copy of the previous version of the file so we can abort if the copy goes wrong
				if (dest.isFile())
					FileUtils.copy(dest::read, () -> dest.getParent().at(injectFileName(dest.getName(), ".OLD")).write());
				FileUtils.copy(source::read, () -> dest.getParent().at(injectFileName(source.getName(), ".NEW")).write());
			} else {
				if (!dest.isDirectory())
					dest.create(true);
				for (BetterFile sub : source.listFiles())
					writeTempPersistentData(sub, dest.at(sub.getName()), written);
			}
		}

		private static String injectFileName(String fileName, String inject) {
			int dot = fileName.indexOf('.');
			if (dot >= 0)
				return new StringBuilder().append(fileName, 0, dot).append(inject).append(fileName, dot, fileName.length()).toString();
			else
				return fileName + ".BAK";
		}

		private static void replaceFiles(Set<BetterFile> written, String injected) throws IOException {
			for (BetterFile file : written) {
				BetterFile source = file.getParent().at(injectFileName(file.getName(), injected));
				file.delete(null);
				source.move(file);
			}
		}

		private static void replaceFilesForceAll(Set<BetterFile> written, String injected) {
			for (BetterFile file : written) {
				BetterFile source = file.getParent().at(injectFileName(file.getName(), injected));
				try {
					file.delete(null);
					source.move(file);
				} catch (IOException e) {
					System.err.println("Failed to revert backup of " + file.getName());
				}
			}
		}

		private static void deleteFiles(Set<BetterFile> written, String injected) {
			for (BetterFile file : written) {
				BetterFile toDelete = file.getParent().at(injectFileName(file.getName(), injected));
				if (toDelete.exists()) {
					try {
						toDelete.delete(null);
					} catch (IOException e) { // Sucks, but keep going
					}
				}
			}
		}

		private class BackupManager implements TemporalBackupScheme.BackupManager<BetterFile, IOException> {
			boolean backupCurrentData;

			@Override
			public Iterable<? extends BetterFile> getCurrentBackups() {
				if (theCurrentDataTime != null)
					return IterableUtils.concat(IterableUtils.single(persistenceDir), persistenceDir.listFiles());
				else
					return persistenceDir.listFiles();
			}

			@Override
			public Instant getDate(BetterFile backup) {
				if (backup == persistenceDir)
					return theCurrentDataTime;
				else if (!backup.isDirectory())
					return null;
				String name = backup.getName();
				if (name.length() != VERSION_DIR_PATTERN.length() + 4 || !name.startsWith("BAK_"))
					return null;
				try {
					return OffsetDateTime.parse(name.substring(4), VERSION_DIR_FORMAT).toInstant();
				} catch (DateTimeParseException e) {
					return null;
				}
			}

			@Override
			public void preserve(BetterFile backup) {
				if (backup == persistenceDir)
					backupCurrentData = true;
			}

			@Override
			public void delete(BetterFile backup) throws IOException {
				backup.delete(null);
			}
		}
	}

	public static class PersistedEntitySet {
		public final BetterFile persistenceDir;
		public final GenericEntitySet entityData;

		public PersistedEntitySet(BetterFile persistenceDir, GenericEntitySet entityData) {
			this.persistenceDir = persistenceDir;
			this.entityData = entityData;
		}
	}

	public static class PersistedMappedEntitySet {
		public final BetterFile persistenceDir;
		public final EntityTypeSetMapping typeMapping;
		public final MappedEntitySet entityData;

		public PersistedMappedEntitySet(BetterFile persistenceDir, EntityTypeSetMapping typeMapping, MappedEntitySet entityData) {
			this.persistenceDir = persistenceDir;
			this.typeMapping = typeMapping;
			this.entityData = entityData;
		}
	}

	public static InitializedDataScheme init(Set<Class<?>> entityTypes, Function<? super Class<?>, String> entityRecognizer,
		Function<? super Method, String> fieldRecognizer, BetterFile codeMigrations) throws IOException, TextParseException {
		BetterSortedSet<MigrationSet> migrations = MigrationPersistence.parseMigrationSets(codeMigrations);
		ModifiableEntityTypeSet migrationSchema = new ModifiableEntityTypeSet();
		for (MigrationSet migrationSet : migrations) {
			for (Migration migration : migrationSet.getMigrations()) {
				if (migration instanceof SchemaMigration)
					((SchemaMigration) migration).applyToSchema(migrationSchema);
			}
		}
		EntityTypeSetMapping mapping;
		try {
			mapping = EntityTypeSetMapping.parseTypeSet(migrationSchema.unmodifiableView(), entityTypes, entityRecognizer, fieldRecognizer);
		} catch (EntityTypeSetMapping.TypeSetMappingException e) {
			throw new MigrationException(
				e.diff.print(new StringBuilder("The entity classes in code are incompatible with the documented schema:\n")).toString(),
				null);
		}
		return new InitializedDataScheme(mapping, migrations);
	}

	public static PersistedEntitySet parseCurrentData(BetterFile rootDataDirectory, EntityTypeSet codeTypes,
		BetterSortedSet<MigrationSet> migrations, BetterFile initDataDir, EntitySetPersistence persistence)
			throws IOException, DataSetMigrationException {
		NavigableMap<Instant, BetterFile> versionDirs = new TreeMap<>();
		for (BetterFile dir : rootDataDirectory.listFiles()) {
			if (dir.isFile() || dir.getName().length() != VERSION_DIR_PATTERN.length()) // Don't read archives
				continue;
			Instant dirTime;
			try {
				dirTime = OffsetDateTime.parse(dir.getName(), VERSION_DIR_FORMAT).toInstant();
				versionDirs.put(dirTime, dir);
			} catch (DateTimeParseException e) { // No worries, just a non-data directory
			}
		}
		String targetDirName = VERSION_DIR_FORMAT.format(migrations.getLast().date);
		BetterFile newDataDirName = rootDataDirectory.at(targetDirName);
		if (newDataDirName.exists()) {
			int suffix = 1;
			do {
				suffix++;
				newDataDirName = rootDataDirectory.at(targetDirName + "_" + suffix);
			} while (newDataDirName.exists());
		}
		PersistedEntitySet currentData = null;
		for (Map.Entry<Instant, BetterFile> versionDir : versionDirs.descendingMap().entrySet()) {
			if (TimeUtils.between(versionDir.getKey(), migrations.getLast().date).compareTo(ONE_SECOND) < 0) {
				// See if this data dir is up-to-date or can be migrated
				currentData = tryToUseDir(versionDir.getValue(), true, newDataDirName, codeTypes, migrations, persistence);
				if (currentData != null)
					break;
			}
		}
		if (currentData == null) {
			BetterFile newDataDir = rootDataDirectory.at(targetDirName);
			if (initDataDir != null)
				currentData = tryToUseDir(initDataDir, false, newDataDir, codeTypes, migrations, persistence);
			if (currentData == null) { // No data to load
				GenericEntitySet dataSet = new InMemoryEntitySet(codeTypes);
				if (!newDataDir.isDirectory())
					newDataDir.create(true);
				currentData = new PersistedEntitySet(newDataDir, dataSet);
			}
		}
		return currentData;
	}

	private static PersistedEntitySet tryToUseDir(BetterFile sourceDataDir, boolean writable, BetterFile destDataDir,
		EntityTypeSet codeTypes, BetterSortedSet<MigrationSet> migrations, EntitySetPersistence persistence)
			throws IOException, DataSetMigrationException {
		MigratedDataSet dataSet = readDataDirectory(sourceDataDir, codeTypes, migrations, persistence);
		if (writable && dataSet.migrationResults == null) {
			// The data was up-to-date and didn't need migration, so we can use it out-of-the-box and persist to the same place.
			return new PersistedEntitySet(sourceDataDir, dataSet.entityData);
		} else {
			// Either the data needed migration or the source directory is not writable, so we need to write it to a new directory
			try {
				persistFullData(dataSet.entityData, migrations, destDataDir, persistence);
			} catch (TextParseException e) {
				// An IOException might be thrown by writing the data, but a parse exception shouldn't happen
				// because we know the entity set is an in-memory implementation.
				throw new IllegalStateException("Failed to persist initial data set", e);
			}
			return new PersistedEntitySet(destDataDir, dataSet.entityData);
		}
	}

	public static class MigratedDataSet {
		public final GenericEntitySet entityData;
		public final MigrationUtil.MigrationDiff migrationResults;
		public MigratedDataSet(GenericEntitySet entityData, MigrationDiff migrationResults) {
			super();
			this.entityData = entityData;
			this.migrationResults = migrationResults;
		}
	}

	public static MigratedDataSet readDataDirectory(BetterFile sourceDataDir, EntityTypeSet codeTypes,
		BetterSortedSet<MigrationSet> migrations, EntitySetPersistence persistence) throws IOException, DataSetMigrationException {
		BetterFile migrationStore = sourceDataDir.at(MIGRATION_STORE);
		BetterFile schemaFile = sourceDataDir.at(DATA_SCHEMA);
		if (!migrationStore.isFile() || !schemaFile.isFile()) {
			throw new DataSetMigrationException(MigrationFailureCause.NotADataSet);
		}

		BetterSortedSet<MigrationSetDef> dataMigrations = BetterTreeSet.createTreeSet(MigrationSetDef.SORT);
		try (TabularFileParser.TypedLineParser3<String, Instant, String> parser = TabularFileParser.parse(migrationStore)//
			.parseTyped().ignoreCase(true)//
			.with("author", false, ExFunction.identity())//
			.with("date", false, (str, p) -> {
				try {
					return OffsetDateTime.parse(str, VERSION_DIR_FORMAT).toInstant();
				} catch (DateTimeParseException e) { // No worries, just a non-data directory
					throw new ParseException("Could not parse " + p.getColumnName(1) + " as a date", p.getPosition(1).getPosition());
				}
			}, "time")//
			.with("description", true, ExFunction.identity(), "descrip")) {
			parser.parseAll(line -> dataMigrations.add(new MigrationSetDef(line.getValue1(), line.getValue2(), line.getValue3())));
		} catch (TextParseException e) {
			throw new DataSetMigrationException(MigrationFailureCause.InvalidDataSet, "Unable to read version record", e);
		}
		MigrationUtil.MigrationDiff migrationDiff = MigrationUtil.diffMigrations(dataMigrations, migrations);
		if (!migrationDiff.unrecognizedMigrations.isEmpty()) {// This data has migrations we don't recognize
			if (migrationDiff.dataSourceAppliedMigration.isEmpty())
				throw new DataSetMigrationException(MigrationFailureCause.IncompatibleDataSet);
			else
				throw new DataSetMigrationException(MigrationFailureCause.IncompatibleVersion);
		} else if (migrationDiff.unappliedMigrations.isEmpty()) { // This data is up-to-date
			GenericEntitySet dataSet = new InMemoryEntitySet(codeTypes);
			try {
				persistence.populate(dataSet, sourceDataDir);
			} catch (TextParseException e) {
				System.err.println("Unable to read data in directory " + sourceDataDir.getPath());
				e.printStackTrace();
				return null;
			}
			return new MigratedDataSet(dataSet, null);
		}
		ModifiableEntityTypeSet dataTypes;
		try {
			dataTypes = MigrationPersistence.readSchema(schemaFile);
		} catch (TextParseException e) {
			throw new DataSetMigrationException(MigrationFailureCause.InvalidDataSet, "Unable to read data schema", e);
		}
		MigratableDataSet dataSet = new InMemoryMigratableEntitySet(dataTypes);
		try {
			persistence.populate(dataSet, sourceDataDir);
		} catch (TextParseException e) {
			throw new DataSetMigrationException(MigrationFailureCause.InvalidDataSet, "Unable to read data content", e);
		}
		for (MigrationSet migration : migrationDiff.unappliedMigrations) {
			try {
				System.out.println("Applying migration " + migration.author + "@" + migration.date + ": " + migration.getDescription());
				MigrationUtil.applyMigrationSet(dataSet, migration);
			} catch (IOException | TextParseException | DataSetModificationException e) {
				throw new IllegalStateException("Unable to migrate data", e);
			}
		}
		MigrationUtil.SchemaDiff schemaDiff = MigrationUtil.diffSchemata(codeTypes, dataTypes);
		if (schemaDiff != null) {
			throw new DataSetMigrationException(MigrationFailureCause.IncompatibleDataSet,
				"Migrated data does not match expected data schema:\n" + schemaDiff.print("code", "data"));
		}
		return new MigratedDataSet(dataSet.immutableSchema(codeTypes), migrationDiff);
	}

	public static void persistFullData(GenericEntitySet dataSet, Collection<? extends MigrationSetDef> migrations, BetterFile directory,
		EntitySetPersistence persistence) throws IOException, TextParseException {
		if (!directory.isDirectory())
			directory.create(true);
		MigrationPersistence.writeSchema(dataSet.getTypes(), directory.at(DATA_SCHEMA));
		try (Writer out = new BufferedWriter(new OutputStreamWriter(directory.at(MIGRATION_STORE).write(), StandardCharsets.UTF_8))) {
			out.write("Author,Date,Description\n");
			for (MigrationSetDef migration : migrations) {
				out.write(CsvParser.toCsv(migration.author, ','));
				out.write(',');
				out.write(CsvParser.toCsv(MigrationPersistence.NO_TZ_DATE_FORMAT.format(migration.date), ','));
				out.write(',');
				out.write(CsvParser.toCsv(migration.getDescription(), ','));
				out.write('\n');
			}
		}
		persistence.persist(dataSet, directory, null);
	}

	public static class EntitySetGenerifier {
		private final EntityTypeSetMapping theTypes;
		private final GenericEntitySet theEntities;
		private Predicate<? super EntityTypeMapping<?>> theExcludedEntities;

		public EntitySetGenerifier(EntityTypeSetMapping types) {
			theTypes = types;
			theEntities = new InMemoryEntitySet(types.getGenericTypes());
		}

		public EntitySetGenerifier exclude(Predicate<? super EntityTypeMapping<?>> excludeEntities) {
			theExcludedEntities = excludeEntities;
			return this;
		}

		public EntitySetGenerifier add(Iterable<?> entities) throws IOException, TextParseException {
			try {
				for (Object entity : entities)
					getOrCreateEntity(entity, null, false);

				Map<Object, Boolean> filledOut = new IdentityHashMap<>();
				for (Object entity : entities)
					populate(entity, null, filledOut);
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new IllegalStateException("Unable to reflectively access entity fields", e);
			}

			return this;
		}

		public GenericEntity getOrCreateEntity(Object entity, EntityTypeMapping<?> superType, boolean inId)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException, TextParseException {
			EntityTypeMapping<?> type = getType(entity, superType);
			if (!inId && theExcludedEntities != null && theExcludedEntities.test(type))
				return null;
			Object[] id = getId(entity, type);
			GenericEntity genericEntity = theEntities.getEntity(type.getName(), id);
			if (genericEntity == null)
				genericEntity = theEntities.createEntity(type.getName(), id);
			return genericEntity;
		}

		public GenericEntitySet getEntities() {
			return theEntities;
		}

		private EntityTypeMapping<?> getType(Object entity, EntityTypeMapping<?> superType) {
			EntityTypeMapping<?> type;
			if (superType != null && (superType.getRealType() == entity.getClass() || superType.getGenericType().getSubTypes().isEmpty()))
				type = superType;
			else {
				type = theTypes.getEntityTypeHierarchy().get(entity.getClass(), TypeMatch.SUPER_TYPE);
				if (type == null)
					throw new IllegalArgumentException(
						"Type " + entity.getClass().getName() + " of entity " + entity + " is not a recognized entity type");
			}
			return type;
		}

		private Object[] getId(Object entity, EntityTypeMapping<?> type)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException, TextParseException {
			Object[] id = new Object[type.getGenericType().getIdFields().size()];
			int i = 0;
			for (EntityFieldMapping<?, ?> field : type.getIdFields()) {
				Object fieldValue = field.getGetter().invoke(entity);
				id[i++] = generifyField(fieldValue, field.getGenericField().getType(), true);
			}
			return id;
		}

		private Object generifyField(Object value, FieldType<?> fieldType, boolean inId)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException, TextParseException {
			if (typeNeedsGenerification(fieldType)) {
				if (value == null) { // No conversion necessary
				} else if (fieldType instanceof EnumType)
					value = theTypes.getEnumTypes().get(((EnumType) fieldType).getName()).getCodeOrderedEnum(((Enum<?>) value).ordinal());
				else if (fieldType instanceof EntityType)
					value = getOrCreateEntity(value, theTypes.getEntityTypes().get(((EntityType) fieldType).getName()), inId);
				else if (fieldType instanceof FieldType.CollectionType)
					value = generifyCollection((Collection<?>) value, (FieldType.CollectionType<?, ?>) fieldType);
				else if (fieldType instanceof FieldType.MapType)
					value = generifyMap((Map<?, ?>) value, (FieldType.MapType<?, ?, ?>) fieldType);
				else if (fieldType instanceof FieldType.MultiMapType)
					value = generifyMultiMap((MultiMap<?, ?>) value, (FieldType.MultiMapType<?, ?, ?>) fieldType);
				else
					throw new IllegalStateException("Unhandled type needing generification?: " + fieldType);
			}
			return value;
		}

		private static boolean typeNeedsGenerification(FieldType<?> fieldType) {
			if (fieldType instanceof EnumType || fieldType instanceof EntityType)
				return true;
			else if (fieldType instanceof FieldType.ParameterizedType) {
				for (FieldType<?> param : ((FieldType.ParameterizedType<?>) fieldType).getTypeParameters()) {
					if (typeNeedsGenerification(param))
						return true;
				}
				return false;
			} else
				return false;
		}

		private <E> Object generifyCollection(Collection<?> value, FieldType.CollectionType<E, ?> fieldType)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, TextParseException, IOException {
			Collection<E> genericCollection = fieldType.createEmptyCollection();
			for (Object element : value)
				genericCollection.add((E) generifyField(element, fieldType.componentType, false));
			return genericCollection;
		}

		private <K, V> Object generifyMap(Map<?, ?> value, FieldType.MapType<K, V, ?> fieldType)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, TextParseException, IOException {
			Map<K, V> genericMap = fieldType.createEmptyMap();
			for (Map.Entry<?, ?> entry : value.entrySet())
				genericMap.put(//
					(K) generifyField(entry.getKey(), fieldType.keyType, false), //
					(V) generifyField(entry.getValue(), fieldType.valueType, false));
			return genericMap;
		}

		private <K, V> Object generifyMultiMap(MultiMap<?, ?> value, FieldType.MultiMapType<K, V, ?> fieldType)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, TextParseException, IOException {
			BetterMultiMap<K, V> genericMap = fieldType.createEmptyMap();
			for (MultiMap.MultiEntry<?, ?> entry : value.entrySet()) {
				for (Object v : entry.getValues()) {
					genericMap.add(//
						(K) generifyField(entry.getKey(), fieldType.keyType, false), //
						(V) generifyField(v, fieldType.valueType, false));
				}
			}
			return genericMap;
		}

		public void populate(Object entity, EntityTypeMapping<?> superType, Map<Object, Boolean> filledOut)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException, TextParseException {
			if (filledOut.put(entity, Boolean.TRUE) != null)
				return;
			EntityTypeMapping<?> type = getType(entity, superType);
			GenericEntity genericEntity = getOrCreateEntity(entity, type, false);
			for (EntityFieldMapping<?, ?> field : type.getFields()) {
				boolean entityField = field.getGenericField() instanceof EntityType;
				Object genericValue = genericEntity.get(field.getGenericField());
				if (!entityField && genericValue != null)
					continue;
				Object fieldValue;
				if (genericValue == null) {
					fieldValue = field.getGetter().invoke(entity);
					fieldValue = generifyField(fieldValue, field.getGenericField().getType(), false);
					genericEntity.set(field.getGenericField(), fieldValue);
				} else if (entityField)
					fieldValue = field.getGetter().invoke(entity);
				else
					fieldValue = null;
				if (entityField && fieldValue != null)
					populate(fieldValue, theTypes.getEntityTypes().get(((EntityType) field.getGenericField()).getName()), filledOut);
			}
		}
	}
}
