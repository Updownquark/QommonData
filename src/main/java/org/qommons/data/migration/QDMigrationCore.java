package org.qommons.data.migration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.qommons.Version;
import org.qommons.config.QonfigInterpretation;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigInterpreterCore;
import org.qommons.config.QonfigInterpreterCore.Builder;
import org.qommons.config.QonfigToolkit;
import org.qommons.config.QonfigToolkitAccess;
import org.qommons.config.SpecialSession;
import org.qommons.data.migration.SchemaMigration.AddEntityMigration;
import org.qommons.data.migration.SchemaMigration.AddEnumMigration;
import org.qommons.data.migration.SchemaMigration.AddFieldMigration;
import org.qommons.data.migration.SchemaMigration.AddValueMigration;
import org.qommons.data.migration.SchemaMigration.ConfiguredFieldMapping;
import org.qommons.data.migration.SchemaMigration.RemoveEntityMigration;
import org.qommons.data.migration.SchemaMigration.RemoveEnumMigration;
import org.qommons.data.migration.SchemaMigration.RemoveFieldMigration;
import org.qommons.data.migration.SchemaMigration.RemoveValueMigration;
import org.qommons.data.migration.SchemaMigration.RenameEntityMigration;
import org.qommons.data.migration.SchemaMigration.RenameEnumMigration;
import org.qommons.data.migration.SchemaMigration.RenameFieldMigration;
import org.qommons.io.LocatedPositionedContent;

public class QDMigrationCore implements QonfigInterpretation {
	public static final String CORE_NAME = "QMigration-Core";
	public static final Version CORE_VERSION = new Version(0, 9, 0);
	public static final String CORE = "QMigration-Core v0.9";
	public static String DATE_FORMAT_PATTERN = "ddMMMyyyy HH:mm:ss";
	public static final DateTimeFormatter TZ_DATE_FORMAT = DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN + " zzz")
		.withZone(ZoneId.of("GMT"));
	public static final DateTimeFormatter NO_TZ_DATE_FORMAT = DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN)
		.withZone(ZoneId.of("GMT"));
	public static final QonfigToolkitAccess CORE_MIGRATIONS = new QonfigToolkitAccess(QDMigrationCore.class, "qommon-core-migrations.qtd");

	@Override
	public Set<Class<? extends SpecialSession<?>>> getExpectedAPIs() {
		return Collections.emptySet();
	}

	@Override
	public String getToolkitName() {
		return CORE_NAME;
	}

	@Override
	public Version getVersion() {
		return CORE_VERSION;
	}

	@Override
	public void init(QonfigToolkit toolkit) {
	}

	@Override
	public Builder configureInterpreter(QonfigInterpreterCore.Builder interpreter) {
		interpreter.createWith("schema-history", SchemaHistory.class, SchemaHistory::new);
		interpreter.createWith("migration-set", MigrationSet.class, session -> {
			LocatedPositionedContent dateContent = session.attributes().get("date").getLocatedContent();
			Instant date;
			try {
				date = parseMigrationTime(dateContent);
			} catch (DateTimeParseException e) {
				throw new QonfigInterpretationException("Could not parse migration date: " + e.getMessage(), dateContent, e);
			}
			List<Migration> migrations = new ArrayList<>();
			MigrationSet migSet = new MigrationSet(session.getAttributeText("author"), date, session.getAttributeText("description"),
				Collections.unmodifiableList(migrations));
			session.put(SchemaMigration.MIGRATION_SET_KEY, migSet);
			for (QonfigInterpreterCore.CoreSession migSession : session.forChildren("migrations")) {
				migrations.add(migSession.interpret(Migration.class));
			}
			return migSet;
		});
		interpreter.createWith("entity-schema", EntitySchema.class, EntitySchema::new);
		interpreter.createWith("add-entity", AddEntityMigration.class, AddEntityMigration::new);
		interpreter.createWith("entity", AddEntityMigration.class, AddEntityMigration::new);
		interpreter.createWith("remove-entity", RemoveEntityMigration.class, RemoveEntityMigration::new);
		interpreter.createWith("rename-entity", RenameEntityMigration.class, RenameEntityMigration::new);
		interpreter.createWith("add-field", AddFieldMigration.class, AddFieldMigration::new);
		interpreter.createWith("field", AddFieldMigration.class, AddFieldMigration::new);
		interpreter.createWith("mapped", ConfiguredFieldMapping.class, ConfiguredFieldMapping::new);
		interpreter.createWith("remove-field", RemoveFieldMigration.class, RemoveFieldMigration::new);
		interpreter.createWith("rename-field", RenameFieldMigration.class, RenameFieldMigration::new);
		interpreter.createWith("add-enum", AddEnumMigration.class, AddEnumMigration::new);
		interpreter.createWith("enum", AddEnumMigration.class, AddEnumMigration::new);
		interpreter.createWith("remove-enum", RemoveEnumMigration.class, RemoveEnumMigration::new);
		interpreter.createWith("rename-enum", RenameEnumMigration.class, RenameEnumMigration::new);
		interpreter.createWith("add-value", AddValueMigration.class, AddValueMigration::new);
		interpreter.createWith("value", AddValueMigration.class, AddValueMigration::new);
		interpreter.createWith("remove-value", RemoveValueMigration.class, RemoveValueMigration::new);
		interpreter.createWith("rename-value", RenameFieldMigration.class, RenameFieldMigration::new);
		interpreter.createWith("for-each", ForEachMigration.class, ForEachMigration::new);
		interpreter.createWith("copy", CopyMigrator.class, CopyMigrator::new);
		interpreter.createWith("Set", SetMigrator.class, SetMigrator::new);
		return interpreter;
	}

	public static Instant parseMigrationTime(CharSequence dateStr) throws DateTimeParseException {
		DateTimeFormatter format;
		if (dateStr.length() > DATE_FORMAT_PATTERN.length())
			format = TZ_DATE_FORMAT;
		else
			format = NO_TZ_DATE_FORMAT;
		LocalDateTime localTime = LocalDateTime.from(format.parse(dateStr));
		return localTime.atOffset(ZoneOffset.UTC).toInstant();
	}
}