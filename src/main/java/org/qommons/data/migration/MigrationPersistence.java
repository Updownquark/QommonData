package org.qommons.data.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.qommons.QommonsUtils;
import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterSortedSet;
import org.qommons.config.StrictXmlReader;
import org.qommons.data.types.MigrationSetDef;
import org.qommons.io.BetterFile;
import org.qommons.io.TextParseException;
import org.qommons.tree.BetterTreeSet;

public class MigrationPersistence {
	public static String DATE_FORMAT_PATTERN = "ddMMMyyyy HH:mm:ss";
	public static final DateTimeFormatter TZ_DATE_FORMAT = DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN + " zzz");
	public static final DateTimeFormatter NO_TZ_DATE_FORMAT = DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN);

	public static BetterSortedSet<MigrationSet> parseMigrationSets(String fileLocation, InputStream in)
		throws IOException, TextParseException {
		return parseMigrationSets(StrictXmlReader.ofRoot(fileLocation, in));
	}

	public static BetterSortedSet<MigrationSet> parseMigrationSets(String fileLocation, Reader in) throws IOException, TextParseException {
		return parseMigrationSets(StrictXmlReader.ofRoot(fileLocation, in));
	}

	public static BetterSortedSet<MigrationSet> parseMigrationSets(BetterFile file) throws IOException, TextParseException {
		try (InputStream in = file.read()) {
			return parseMigrationSets(StrictXmlReader.ofRoot(file.getName(), in));
		}
	}

	private static BetterSortedSet<MigrationSet> parseMigrationSets(StrictXmlReader xml) throws TextParseException {
		if (!"migrations".equals(xml.getName()))
			throw new TextParseException("Expected 'migrations' as the root element, not '" + xml.getName() + "'",
				xml.getNamePosition().getPosition(0));
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null)
			classLoader = MigrationPersistence.class.getClassLoader();
		BetterSortedSet<MigrationSet> migrationSets = BetterTreeSet.<MigrationSet> buildTreeSet(MigrationSetDef.SORT).build();
		Map<String, Class<?>> classCache = new HashMap<>();
		for (StrictXmlReader msEl : xml.getElements("migration-set")) {
			MigrationSet ms = parseMigrationSet(msEl, classCache, classLoader);
			if (!migrationSets.add(ms)) {
				throw new IllegalStateException("Duplicate migration set identities present: " + ms);
			}
		}
		xml.check(true);
		return BetterCollections.unmodifiableSortedSet(migrationSets);
	}

	private static MigrationSet parseMigrationSet(StrictXmlReader msEl, Map<String, Class<?>> classCache, ClassLoader classLoader)
		throws TextParseException {
		String author = msEl.getAttribute("author");
		String dateStr = msEl.getAttribute("date");
		String descrip = msEl.getAttribute("description");

		Instant date;
		try {
			if (dateStr.length() > DATE_FORMAT_PATTERN.length())
				date = OffsetDateTime.parse(dateStr, TZ_DATE_FORMAT).toInstant();
			else
				date = OffsetDateTime.parse(dateStr, NO_TZ_DATE_FORMAT).toInstant();
		} catch (DateTimeParseException e) {
			throw new TextParseException("Could not parse migration-set.date: '" + dateStr + "'",
				msEl.getAttributeNamePosition("date").getPosition(0), e);
		}

		Map<String, ConfigurableCustomMigrator<?>> migrators = new LinkedHashMap<>();
		List<Migration> migrations = new ArrayList<>();
		MigrationSet migSet = new MigrationSet(author, date, descrip, Collections.unmodifiableMap(migrators),
			Collections.unmodifiableList(migrations));
		for (StrictXmlReader migEl : msEl.getElements("migrator")) {
			ConfigurableCustomMigrator<?> migrator = parseMigrator(migEl, classCache, classLoader);
			if (null != migrators.putIfAbsent(migrator.getName(), migrator))
				throw new TextParseException("Duplicate migrators with ref-id '" + migrator.getName() + "'",
					migEl.getNamePosition().getPosition(0));
		}
		for (StrictXmlReader migEl : msEl.getElements())
			migrations.add(parseMigration(migEl, migSet, migrators));

		return migSet;
	}

	private static <M extends CustomMigrationComponent> ConfigurableCustomMigrator<M> parseMigrator(StrictXmlReader xml,
		Map<String, Class<?>> classCache, ClassLoader classLoader) throws TextParseException {
		String refId = xml.getAttribute("ref-id");
		String className = xml.getAttribute("class");
		Class<?> found = classCache.get(className);
		if (found == null) {
			try {
				found = classLoader.loadClass(className);
			} catch (ClassNotFoundException e) {
				throw new TextParseException("No such class found: " + className, xml.getAttributeValuePosition("class").getPosition(0));
			}
			classCache.put(className, found);
		}
		return new ConfigurableCustomMigrator<>(refId, (Class<? extends M>) found, xml, parseRequiredFields(xml, null));
	}

	private static Migration parseMigration(StrictXmlReader xml, MigrationSet migSet, Map<String, ConfigurableCustomMigrator<?>> migrators)
		throws TextParseException {
		switch (xml.getName()) {
		case "add-entity":
			return parseAddEntityMigration(xml, migSet, migrators);
		case "remove-entity":
			return parseRemoveEntityMigration(xml, migSet, migrators);
		case "rename-entity":
			return new SchemaMigration.RenameEntityMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("entity"),
				xml.getAttribute("rename-to"));
		case "add-field":
			return parseAddFieldMigration(xml, migSet, xml.getAttribute("entity"), migrators);
		case "remove-field":
			return new SchemaMigration.RemoveFieldMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("entity"),
				xml.getAttribute("field"));
		case "rename-field":
			return new SchemaMigration.RenameFieldMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("entity"),
				xml.getAttribute("field"), xml.getAttribute("rename-to"));
		case "add-enum":
			return new SchemaMigration.AddEnumMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("enum"));
		case "remove-enum":
			return new SchemaMigration.RemoveEnumMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("enum"));
		case "rename-enum":
			return new SchemaMigration.RenameEnumMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("enum"),
				xml.getAttribute("rename-to"));
		case "add-value":
			return new SchemaMigration.AddValueMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("enum"),
				xml.getAttribute("value"));
		case "remove-value":
			return new SchemaMigration.RemoveValueMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("enum"),
				xml.getAttribute("value"));
		case "rename-value":
			return new SchemaMigration.RenameValueMigration(migSet, xml.getNamePosition().getPosition(0), xml.getAttribute("enum"),
				xml.getAttribute("value"), xml.getAttribute("rename-to"));
		case "for-each":
			String entity = xml.getAttribute("entity");
			ConfigurableCustomMigrator<?> migrator = migrators.get(xml.getAttribute("migrator"));
			if (migrator == null)
				throw new TextParseException("No such migrator found with ref-id '" + xml.getAttribute("migrator") + "'",
					xml.getAttributeValuePosition("migrator").getPosition(0));
			else if (!SingleEntityCustomMigrator.class.isAssignableFrom(migrator.migrator))
				throw new TextParseException("Migrator " + migrator + " is not an instance of " + SingleEntityCustomMigrator.class.getName()
					+ ", which is required for for-each migration", xml.getAttributeValuePosition("migrator").getPosition(0));
			return new CustomMigration.ForEachMigration(migSet, xml.getNamePosition().getPosition(0), entity,
				parseAffectedEntities(xml, null), parseRequiredFields(xml, Collections.singletonMap(entity, Collections.emptySet())),
				(ConfigurableCustomMigrator<SingleEntityCustomMigrator>) migrator);
		case "custom":
			migrator = migrators.get(xml.getAttribute("migrator"));
			if (migrator == null)
				throw new TextParseException("No such migrator found with ref-id '" + xml.getAttribute("migrator") + "'",
					xml.getAttributeValuePosition("migrator").getPosition(0));
			else if (!WholeSetCustomMigrator.class.isAssignableFrom(migrator.migrator))
				throw new TextParseException("Migrator " + migrator + " is not an instance of " + WholeSetCustomMigrator.class.getName()
					+ ", which is required for custom migration", xml.getAttributeValuePosition("migrator").getPosition(0));
			return new CustomMigration.WholeSetCustomMigration(migSet, xml.getNamePosition().getPosition(0),
				parseAffectedEntities(xml, null), parseRequiredFields(xml, null),
				(ConfigurableCustomMigrator<WholeSetCustomMigrator>) migrator);
		default:
			throw new TextParseException("Unrecognized migration '" + xml.getName() + "'", xml.getNamePosition().getPosition(0));
		}
	}

	private static Set<String> parseAffectedEntities(StrictXmlReader xml, Set<String> init) throws TextParseException {
		Set<String> affected = new LinkedHashSet<>();
		if (init != null)
			affected.addAll(init);
		for (StrictXmlReader entity : xml.getElements("affects"))
			affected.add(entity.getTextTrim());
		return Collections.unmodifiableSet(affected);
	}

	private static Map<String, Set<String>> parseRequiredFields(StrictXmlReader xml, Map<String, Set<String>> init)
		throws TextParseException {
		Map<String, Set<String>> requiredFields = new LinkedHashMap<>();
		Set<String> entityFields = new LinkedHashSet<>();
		for (StrictXmlReader requiredEntity : xml.getElements("required-entity")) {
			String name = requiredEntity.getAttribute("name");
			if (requiredFields.containsKey(name))
				throw new TextParseException("Duplicate required-entity '" + name + "'",
					requiredEntity.getAttributeValuePosition("name").getPosition(0));
			for (StrictXmlReader requiredField : requiredEntity.getElements("required-field")) {
				String field = requiredField.getAttribute("name");
				if (requiredFields.containsKey(name))
					throw new TextParseException("Duplicate required-field '" + field + " for required-entity '" + name + "'",
						requiredField.getAttributeValuePosition("name").getPosition(0));
				entityFields.add(field);
			}
			if (init != null) {
				entityFields.addAll(init.getOrDefault(name, Collections.emptySet()));
			}
			requiredFields.put(name, QommonsUtils.unmodifiableDistinctCopy(entityFields));
			entityFields.clear();
		}
		if (init != null) {
			for (Map.Entry<String, Set<String>> entity : init.entrySet()) {
				if (!requiredFields.containsKey(entity.getKey()))
					requiredFields.put(entity.getKey(), entity.getValue());
			}
		}
		return Collections.unmodifiableMap(requiredFields);
	}

	private static SchemaMigration.AddEntityMigration parseAddEntityMigration(StrictXmlReader xml, MigrationSet migSet,
		Map<String, ConfigurableCustomMigrator<?>> migrators) throws TextParseException {
		String entity = xml.getAttribute("name");
		String[] idFields = xml.getAttribute("id").split("\\s*,\\s*");
		if (idFields.length == 0)
			throw new TextParseException("add-entity.id cannot be empty", xml.getAttributeValuePosition("id").getPosition(0));
		Map<String, SchemaMigration.AddFieldMigration> fields = new LinkedHashMap<>();
		for (StrictXmlReader fieldXml : xml.getElements("field")) {
			SchemaMigration.AddFieldMigration field = parseAddFieldMigration(fieldXml, migSet, entity, migrators);
			if (fields.containsKey(field.fieldName))
				throw new TextParseException("Duplicate fields named '" + field.fieldName + "'",
					fieldXml.getAttributeValuePosition("name").getPosition(0));

			fields.put(field.fieldName, field);
		}
		for (String id : idFields) {
			if (!fields.containsKey(id))
				throw new TextParseException("ID field '" + id + "' not declared", xml.getAttributeValuePosition("id").getPosition(0));
		}
		return new SchemaMigration.AddEntityMigration(migSet, xml.getNamePosition().getPosition(0), entity,
			xml.getAttributeIfExists("super"), QommonsUtils.unmodifiableDistinctCopy(idFields),
			QommonsUtils.unmodifiableCopy(fields.values()));
	}

	private static SchemaMigration.AddFieldMigration parseAddFieldMigration(StrictXmlReader xml, MigrationSet migSet, String entity,
		Map<String, ConfigurableCustomMigrator<?>> migrators) throws TextParseException {
		String initValue = xml.getAttributeIfExists("init-value");
		String initWith = xml.getAttributeIfExists("init-with");
		ConfigurableCustomMigrator<?> migrator = migrators.get(initWith);
		if (initWith != null) {
			if (migrator == null)
				throw new TextParseException("No such migrator found with ref-id '" + initWith + "'",
					xml.getAttributeValuePosition("init-with").getPosition(0));
			else if (!EntityFieldInitializer.class.isAssignableFrom(migrator.migrator))
				throw new TextParseException("Migrator " + migrator + " is not an instance of " + EntityFieldInitializer.class.getName()
					+ ", which is required to initialize field values", xml.getAttributeValuePosition("init-with").getPosition(0));
		}
		return new SchemaMigration.AddFieldMigration(migSet, xml.getNamePosition().getPosition(0), entity, xml.getAttribute("name"),
			xml.getAttribute("type"), initValue, (ConfigurableCustomMigrator<EntityFieldInitializer>) migrator);
	}

	private static SchemaMigration.RemoveEntityMigration parseRemoveEntityMigration(StrictXmlReader xml, MigrationSet migSet,
		Map<String, ConfigurableCustomMigrator<?>> migrators) throws TextParseException {
		String entity = xml.getAttribute("name");
		StrictXmlReader moveToXml = xml.getElementIfExists("move-to");
		if (moveToXml == null)
			return new SchemaMigration.RemoveEntityMigration(migSet, xml.getNamePosition().getPosition(0), entity, null);
		String target = moveToXml.getAttribute("target");
		ConfigurableCustomMigrator<?> migrator = migrators.get(xml.getAttribute("migrator"));
		if (migrator == null)
			throw new TextParseException("No such migrator found with ref-id '" + xml.getAttribute("migrator") + "'",
				xml.getAttributeValuePosition("migrator").getPosition(0));
		else if (!EntityMoveMigrator.class.isAssignableFrom(migrator.migrator))
			throw new TextParseException("Migrator " + migrator + " is not an instance of " + EntityMoveMigrator.class.getName()
				+ ", which is required to migrate removed entity values", xml.getAttributeValuePosition("migrator").getPosition(0));
		return new SchemaMigration.RemoveEntityMigration(migSet, xml.getNamePosition().getPosition(0), entity,
			new SchemaMigration.EntityMove(target, parseAffectedEntities(moveToXml, QommonsUtils.unmodifiableDistinctCopy(target)),
				parseRequiredFields(moveToXml, null), (ConfigurableCustomMigrator<EntityMoveMigrator>) migrator));
	}
}
