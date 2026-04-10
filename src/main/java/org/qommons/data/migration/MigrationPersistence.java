package org.qommons.data.migration;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import java.util.regex.Pattern;

import org.qommons.Named;
import org.qommons.QommonsUtils;
import org.qommons.StringUtils;
import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterSet;
import org.qommons.collect.BetterSortedSet;
import org.qommons.collect.CollectionElement;
import org.qommons.config.StrictXmlReader;
import org.qommons.data.migration.SchemaMigration.AddEntityMigration;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;
import org.qommons.data.types.modifiable.ModifiableEntityType;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.io.BetterFile;
import org.qommons.io.TextParseException;
import org.qommons.io.XmlSerialWriter;
import org.qommons.tree.BetterTreeSet;

public class MigrationPersistence {
	public static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z_$][a-zA-Z\\d_$]*$");
	public static final Set<String> RESERVED_TYPES = QommonsUtils.unmodifiableDistinctCopy("boolean", "char", "byte", "short", "int",
		"long", "float", "double", "String");
	public static String DATE_FORMAT_PATTERN = "ddMMMyyyy HH:mm:ss";
	public static final DateTimeFormatter TZ_DATE_FORMAT = DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN + " zzz")
		.withZone(ZoneId.of("GMT"));
	public static final DateTimeFormatter NO_TZ_DATE_FORMAT = DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN).withZone(ZoneId.of("GMT"));

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
		return new ConfigurableCustomMigrator<>(refId, (Class<? extends M>) found, xml);
	}

	private static Migration parseMigration(StrictXmlReader xml, MigrationSet migSet, Map<String, ConfigurableCustomMigrator<?>> migrators)
		throws TextParseException {
		switch (xml.getName()) {
		case "add-entity":
			return parseAddEntityMigration(xml, migSet);
		case "remove-entity":
			return parseRemoveEntityMigration(xml, migSet, migrators);
		case "rename-entity":
			return new SchemaMigration.RenameEntityMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "entity"),
				getIdentifier(xml, "rename-to"));
		case "add-field":
			return parseAddFieldMigration(xml, migSet, getIdentifier(xml, "entity"), migrators);
		case "remove-field":
			return new SchemaMigration.RemoveFieldMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "entity"),
				getIdentifier(xml, "field"));
		case "rename-field":
			return new SchemaMigration.RenameFieldMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "entity"),
				getIdentifier(xml, "field"), getIdentifier(xml, "rename-to"));
		case "add-enum":
			return parseAddEnumMigration(xml, migSet);
		case "remove-enum":
			return new SchemaMigration.RemoveEnumMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "name"));
		case "rename-enum":
			return new SchemaMigration.RenameEnumMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "enum"),
				getIdentifier(xml, "rename-to"));
		case "add-value":
			return new SchemaMigration.AddValueMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "enum"),
				getIdentifier(xml, "value"));
		case "remove-value":
			return new SchemaMigration.RemoveValueMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "enum"),
				getIdentifier(xml, "value"));
		case "rename-value":
			return new SchemaMigration.RenameValueMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "enum"),
				getIdentifier(xml, "value"), getIdentifier(xml, "rename-to"));
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

	private static String getIdentifier(StrictXmlReader xml, String attribute) throws TextParseException {
		String text;
		if (attribute != null)
			text = xml.getAttribute(attribute);
		else
			text = xml.getTextTrim();
		if (!IDENTIFIER.matcher(text).matches()) {
			if (attribute != null)
				throw new TextParseException(
					"Attribute '" + attribute + "' must be an identifier: " + IDENTIFIER.pattern() + ", not '" + text + "'",
					xml.getAttributeValuePosition(attribute).getPosition(0));
			else
				throw new TextParseException("Content must be an identifier: " + IDENTIFIER.pattern() + ", not '" + text + "'",
					xml.getTextTrimPosition().getPosition(0));
		} else if (RESERVED_TYPES.contains(text))
			throw new TextParseException("'" + text + "' is a reserved word", xml.getAttributeValuePosition(attribute).getPosition(0));
		return text;
	}

	private static Set<String> parseAffectedEntities(StrictXmlReader xml, Set<String> init) throws TextParseException {
		Set<String> affected = new LinkedHashSet<>();
		if (init != null)
			affected.addAll(init);
		for (StrictXmlReader entity : xml.getElements("affects"))
			affected.add(getIdentifier(entity, null));
		return Collections.unmodifiableSet(affected);
	}

	private static Map<String, Set<String>> parseRequiredFields(StrictXmlReader xml, Map<String, Set<String>> init)
		throws TextParseException {
		Map<String, Set<String>> requiredFields = new LinkedHashMap<>();
		Set<String> entityFields = new LinkedHashSet<>();
		for (StrictXmlReader requiredEntity : xml.getElements("required-entity")) {
			String name = getIdentifier(requiredEntity, "name");
			if (requiredFields.containsKey(name))
				throw new TextParseException("Duplicate required-entity '" + name + "'",
					requiredEntity.getAttributeValuePosition("name").getPosition(0));
			for (StrictXmlReader requiredField : requiredEntity.getElements("required-field")) {
				String field = getIdentifier(requiredField, "name");
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

	public static SchemaMigration.AddEntityMigration parseAddEntityMigration(StrictXmlReader xml, MigrationSet migSet)
		throws TextParseException {
		String entity = getIdentifier(xml, "name");
		String superType = xml.getAttributeIfExists("super");
		String idFieldsStr = xml.getAttributeIfExists("id");
		if (superType != null) {
			if (!IDENTIFIER.matcher(superType).matches())
				throw new TextParseException("Attribute super must be an identifier: " + IDENTIFIER,
					xml.getAttributeValuePosition("super").getPosition(0));
			if (idFieldsStr != null)
				throw new TextParseException("Entity types with a super type must inherit their super type's id",
					xml.getAttributeValuePosition("id").getPosition(0));
		} else if (idFieldsStr == null)
			throw new TextParseException("New entity types must have either a super type or id fields",
				xml.getNamePosition().getPosition(0));

		Map<String, SchemaMigration.AddFieldMigration> fields = new LinkedHashMap<>();
		for (StrictXmlReader fieldXml : xml.getElements("field")) {
			SchemaMigration.AddFieldMigration field = parseAddFieldMigration(fieldXml, migSet, entity, null);
			if (fields.containsKey(field.fieldName))
				throw new TextParseException("Duplicate fields named '" + field.fieldName + "'",
					fieldXml.getAttributeValuePosition("name").getPosition(0));

			fields.put(field.fieldName, field);
		}
		Set<String> idFields;
		if (idFieldsStr != null) {
			String[] idFieldSplit = idFieldsStr.split("\\s*,\\s*");
			if (idFieldSplit.length == 0)
				throw new TextParseException("add-entity.id cannot be empty", xml.getAttributeValuePosition("id").getPosition(0));
			for (String id : idFieldSplit) {
				if (!fields.containsKey(id))
					throw new TextParseException("ID field '" + id + "' not declared", xml.getAttributeValuePosition("id").getPosition(0));
			}
			idFields = QommonsUtils.unmodifiableDistinctCopy(idFieldSplit);
		} else
			idFields = Collections.emptySet();
		return new SchemaMigration.AddEntityMigration(migSet, xml.getNamePosition().getPosition(0), entity, superType, idFields,
			QommonsUtils.unmodifiableCopy(fields.values()));
	}

	private static SchemaMigration.AddFieldMigration parseAddFieldMigration(StrictXmlReader xml, MigrationSet migSet, String entity,
		Map<String, ConfigurableCustomMigrator<?>> migrators) throws TextParseException {
		String initValue = xml.getAttributeIfExists("init-value");
		String initWith = xml.getAttributeIfExists("init-with");
		if (migrators == null) { // Field declaration within an add-entity migration
			if (initValue != null)
				throw new TextParseException("Fields cannot be initialized for new entity types, since there are initially no instances",
					xml.getAttributeValuePosition("init-value").getPosition(0));
			if (initWith != null)
				throw new TextParseException("Fields cannot be initialized for new entity types, since there are initially no instances",
					xml.getAttributeValuePosition("init-with").getPosition(0));
		} else if (initValue == null && initWith == null) {
			throw new TextParseException("Added fields must be initialized via 'init-value=\"<value>\"' and/or 'init-with=\"<migrator>\"'",
				xml.getNamePosition().getPosition(0));
		}
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
			xml.getAttribute("type"), initValue, (ConfigurableCustomMigrator<EntityFieldInitializer>) migrator,
			parseRequiredFields(xml, null));
	}

	private static SchemaMigration.RemoveEntityMigration parseRemoveEntityMigration(StrictXmlReader xml, MigrationSet migSet,
		Map<String, ConfigurableCustomMigrator<?>> migrators) throws TextParseException {
		String entity = getIdentifier(xml, "name");
		StrictXmlReader moveToXml = xml.getElementIfExists("move-to");
		if (moveToXml == null)
			return new SchemaMigration.RemoveEntityMigration(migSet, xml.getNamePosition().getPosition(0), entity, null);
		String target = getIdentifier(moveToXml, "target");
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

	public static SchemaMigration.AddEnumMigration parseAddEnumMigration(StrictXmlReader xml, MigrationSet migSet)
		throws TextParseException {
		Set<String> initialValues = new LinkedHashSet<>();
		for (StrictXmlReader valueEl : xml.getElements("value")) {
			initialValues.add(valueEl.getAttribute("name"));
		}
		return new SchemaMigration.AddEnumMigration(migSet, xml.getNamePosition().getPosition(0), getIdentifier(xml, "name"),
			Collections.unmodifiableSet(initialValues));
	}

	public static ModifiableEntityTypeSet readSchema(BetterFile schemaFile) throws IOException, TextParseException {
		ModifiableEntityTypeSet typeSet = new ModifiableEntityTypeSet();
		StrictXmlReader xml;
		try (InputStream in = schemaFile.read()) {
			xml = StrictXmlReader.ofRoot(schemaFile.getPath(), in);
		}
		if (!xml.getName().equals("entity-schema"))
			throw new TextParseException("Expected 'entity-schema' root, not '" + xml.getName() + "'",
				xml.getNamePosition().getPosition(0));

		for (StrictXmlReader enumXml : xml.getElements("enum"))
			MigrationPersistence.parseAddEnumMigration(enumXml, null).validate(typeSet, Collections.emptyMap());

		Map<String, ParsingEntityType> entityTypes = new HashMap<>();
		for (StrictXmlReader entityXml : xml.getElements("entity")) {
			SchemaMigration.AddEntityMigration add = MigrationPersistence.parseAddEntityMigration(entityXml, null);
			entityTypes.put(add.entityName, new ParsingEntityType(add));
		}
		BetterSet<String> path = BetterHashSet.create();
		for (ParsingEntityType type : entityTypes.values()) {
			if (!type.parsed)
				createEntityType(type, typeSet, entityTypes, path);
		}
		for (ModifiableEntityType type : typeSet.getEntityTypes()) {
			ParsingEntityType add = entityTypes.get(type.getName());
			for (SchemaMigration.AddFieldMigration field : add.add.fields.values()) {
				if (!add.add.idFieldNames.contains(field.fieldName))
					field.validate(typeSet, Collections.emptyMap());
			}
		}
		xml.check(true);
		return typeSet;
	}

	private static ModifiableEntityType createEntityType(ParsingEntityType add, ModifiableEntityTypeSet typeSet,
		Map<String, ParsingEntityType> parsing, BetterSet<String> path) throws MigrationException {
		CollectionElement<String> pathAdded = path.addElement(add.add.entityName, null, null, false);
		if (pathAdded == null)
			throw new MigrationException("Entity ID cycle: " + path, add.add.getPosition());
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

	static class ParsingEntityType {
		final SchemaMigration.AddEntityMigration add;
		boolean parsed;

		ParsingEntityType(AddEntityMigration add) {
			this.add = add;
		}
	}

	public static void writeSchema(EntityTypeSet dataTypes, BetterFile schemaFile) throws IOException {
		try (Writer out = new BufferedWriter(new OutputStreamWriter(schemaFile.write(), StandardCharsets.UTF_8))) {
			XmlSerialWriter.createDocument(out).setEncoding("UTF-8").writeRoot("entity-schema", root -> {
				// Enums first
				for (EnumType enumType : dataTypes.getEnumTypes()) {
					root.addChild("enum", enumXml -> {
						enumXml.addAttribute("name", enumType.getName());
						for (EnumValue value : enumType.getValues())
							enumXml.addChild("value", valueXml -> valueXml.addAttribute("name", value.getName()));
					});
				}

				// Then entity types
				for (EntityType entityType : dataTypes.getEntityTypes()) {
					root.addChild("entity", entityXml -> {
						entityXml.addAttribute("name", entityType.getName());
						if (entityType.getSuperType() != null)
							entityXml.addAttribute("super", entityType.getSuperType().getName());
						else
							entityXml.addAttribute("id", StringUtils.print(",", entityType.getIdFields(), Named::getName).toString());
						for (EntityField<?> field : entityType.getLocalFields()) {
							entityXml.addChild("field", fieldXml -> {
								fieldXml//
								.addAttribute("name", field.getName())//
								.addAttribute("type", field.getType().toString());
							});
						}
					});
				}
			});
		}
	}
}
