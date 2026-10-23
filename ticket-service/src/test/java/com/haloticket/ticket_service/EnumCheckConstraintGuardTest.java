package com.haloticket.ticket_service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hibernate (ddl-auto: update) writes a CHECK constraint for every enum column when it creates the table and never changes
 * it. A value added to a Java enum later is then refused by the database until someone writes a SQL migration.
 *
 * One guard for all seven services, driven by the source files (the services are separate Maven projects, so there is no
 * shared JPA model to ask). It checks three things:
 *  1. a value that production's constraint rejects must be listed by a migration;
 *  2. a migration that defines an enum constraint must list every value of its enum, always;
 *  3. scripts/audit/enum_check_audit.sql must name every enum value.
 *
 * The production state is the checked-in snapshot haloticket-infrastructure/postgres/audit/production-check-constraints-*.txt.
 * After you apply a migration on production, capture the snapshot again. Limits: it reads source text (a regex, not the
 * compiler), and it cannot see a production database; only the snapshot says what production allows.
 */
class EnumCheckConstraintGuardTest {

    private static final Path BACKEND = Path.of("..").toAbsolutePath().normalize();
    private static final Path MIGRATIONS = BACKEND.resolve("haloticket-infrastructure/postgres/migrations");
    private static final Path SNAPSHOT_DIR = BACKEND.resolve("haloticket-infrastructure/postgres/audit");
    private static final Path AUDIT_SQL = BACKEND.resolve("scripts/audit/enum_check_audit.sql");

    private static final Map<String, String> DATABASE = Map.of(
            "user", "user_db", "event", "event_db", "ticket", "ticket_db", "booking", "booking_db",
            "payment", "payment_db", "notification", "notification_db", "marketplace", "marketplace_db");

    /** An element collection of enums: its values live in a separate table and production has no CHECK on it. */
    private static final Set<String> NOT_AUDITED = Set.of("users.user_roles");

    record EnumColumn(String database, String table, String column, String enumName, List<String> values) {
        String key() { return database + "|" + table + "|" + column; }
        String label() { return database + "." + table + "." + column + " (" + enumName + ")"; }
    }

    record Constraint(String name, List<String> values, String file) {}

    private static List<EnumColumn> enumColumns;
    private static Map<String, Constraint> production;       // key: database|table|column
    private static Map<String, Constraint> latestMigration;  // key: constraint name

    @BeforeAll
    static void load() throws IOException {
        assumeTrue(Files.isDirectory(MIGRATIONS) && Files.isDirectory(SNAPSHOT_DIR) && Files.isRegularFile(AUDIT_SQL),
                "needs the whole backend checkout (migrations, audit snapshot and script)");
        enumColumns = readEnumColumns();
        production = readProductionSnapshot();
        latestMigration = readMigrations();
    }

    // --- the guards ---------------------------------------------------------------------------------------------------

    @Test
    void theParserFindsTheEnumColumnsAndTheProductionSnapshot() {
        assertThat(enumColumns).as("enum columns found in the entity sources").hasSizeGreaterThanOrEqualTo(50);
        assertThat(enumColumns).extracting(EnumColumn::database).containsAll(DATABASE.values());
        assertThat(enumColumns).allSatisfy(column -> assertThat(column.values()).as(column.label()).isNotEmpty());
        assertThat(production).as("constraints in the production snapshot").hasSizeGreaterThanOrEqualTo(35);
        assertThat(latestMigration).as("enum constraints defined by migrations").isNotEmpty();
    }

    @Test
    void everyEnumValueThatProductionRejectsIsListedByAMigration() {
        List<String> problems = new ArrayList<>();
        for (EnumColumn column : enumColumns) {
            Constraint onProduction = production.get(column.key());
            if (onProduction == null) continue; // no CHECK on production: nothing can be rejected
            List<String> rejected = column.values().stream().filter(v -> !onProduction.values().contains(v)).toList();
            if (rejected.isEmpty()) continue;
            Constraint migration = latestMigration.get(onProduction.name());
            if (migration == null || !migration.values().containsAll(column.values())) {
                problems.add(column.label() + ": production's " + onProduction.name() + " rejects " + rejected
                        + (migration == null ? " and no migration defines that constraint"
                        : " and the latest migration (" + migration.file() + ") does not list all values"));
            }
        }
        assertThat(problems).as("write a migration in haloticket-infrastructure/postgres/migrations").isEmpty();
    }

    @Test
    void everyMigrationThatDefinesAnEnumConstraintListsEveryValueOfItsEnum() {
        List<String> problems = new ArrayList<>();
        for (EnumColumn column : enumColumns) {
            String name = production.containsKey(column.key()) ? production.get(column.key()).name() : column.table() + "_" + column.column() + "_check";
            Constraint migration = latestMigration.get(name);
            if (migration == null) continue;
            List<String> unlisted = column.values().stream().filter(v -> !migration.values().contains(v)).toList();
            if (!unlisted.isEmpty()) problems.add(column.label() + ": " + migration.file() + " does not list " + unlisted);
        }
        assertThat(problems).as("update the newest migration for that constraint, or add a new one").isEmpty();
    }

    @Test
    void theAuditScriptNamesEveryEnumValue() throws IOException {
        String sql = Files.readString(AUDIT_SQL);
        Set<String> listed = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\('(\\w+)', '(\\w+)', '(\\w+)'\\)").matcher(sql);
        while (m.find()) listed.add(m.group(1) + "." + m.group(2) + "." + m.group(3));

        List<String> unlisted = new ArrayList<>();
        for (EnumColumn column : enumColumns) {
            if (NOT_AUDITED.contains(column.table() + "." + column.column())) continue;
            for (String value : column.values()) {
                if (!listed.contains(column.table() + "." + column.column() + "." + value)) unlisted.add(column.label() + " " + value);
            }
        }
        assertThat(unlisted).as("regenerate scripts/audit/enum_check_audit.sql").isEmpty();
    }

    // --- parsing --------------------------------------------------------------------------------------------------------

    private static List<EnumColumn> readEnumColumns() throws IOException {
        List<EnumColumn> columns = new ArrayList<>();
        Pattern table = Pattern.compile("@Table\\(\\s*name\\s*=\\s*\"(\\w+)\"");
        Pattern field = Pattern.compile("@Enumerated\\(EnumType\\.STRING\\)(.*?)private\\s+([\\w<>.]+)\\s+(\\w+)\\s*(?:=[^;]*)?;", Pattern.DOTALL);
        Pattern explicitColumn = Pattern.compile("name\\s*=\\s*\"(\\w+)\"");
        for (Map.Entry<String, String> service : DATABASE.entrySet()) {
            Path source = BACKEND.resolve(service.getKey() + "-service/src/main/java");
            if (!Files.isDirectory(source)) continue;
            Map<String, List<String>> enums = readEnums(source);
            for (Path entity : javaFiles(source, "entity")) {
                String text = Files.readString(entity);
                Matcher tableName = table.matcher(text);
                if (!tableName.find()) continue;
                Matcher fields = field.matcher(text);
                while (fields.find()) {
                    Matcher column = explicitColumn.matcher(fields.group(1));
                    String enumName = fields.group(2).replaceAll(".*<(\\w+)>.*", "$1");
                    columns.add(new EnumColumn(service.getValue(), tableName.group(1),
                            column.find() ? column.group(1) : snake(fields.group(3)), enumName, enums.getOrDefault(enumName, List.of())));
                }
            }
        }
        return columns;
    }

    private static Map<String, List<String>> readEnums(Path source) throws IOException {
        Map<String, List<String>> enums = new HashMap<>();
        Pattern declaration = Pattern.compile("enum\\s+(\\w+)\\s*\\{([^;}]*)");
        for (Path file : javaFiles(source, "")) {
            Matcher m = declaration.matcher(Files.readString(file));
            while (m.find()) {
                String body = m.group(2).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                List<String> values = new ArrayList<>();
                for (String token : body.split(",")) {
                    String name = token.strip().split("[(\\s]")[0];
                    if (name.matches("[A-Z][A-Z0-9_]*")) values.add(name);
                }
                enums.put(m.group(1), values);
            }
        }
        return enums;
    }

    private static List<Path> javaFiles(Path root, String folder) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> folder.isEmpty() || p.getParent().getFileName().toString().equals(folder)).toList();
        }
    }

    private static Map<String, Constraint> readProductionSnapshot() throws IOException {
        Path snapshot;
        try (Stream<Path> files = Files.list(SNAPSHOT_DIR)) {
            snapshot = files.filter(p -> p.getFileName().toString().startsWith("production-check-constraints-")).sorted().reduce((a, b) -> b).orElseThrow();
        }
        Map<String, Constraint> result = new LinkedHashMap<>();
        Pattern column = Pattern.compile("CHECK \\(\\(\\((\\w+)\\)::text = ANY");
        Pattern value = Pattern.compile("'([A-Z0-9_]+)'::character varying");
        String database = null;
        for (String line : Files.readAllLines(snapshot)) {
            if (line.startsWith("-- DATABASE:")) { database = line.substring("-- DATABASE:".length()).strip(); continue; }
            if (line.startsWith("--") || line.isBlank()) continue;
            String[] parts = line.split(" \\| ", 3);
            if (parts.length != 3 || database == null) continue;
            Matcher c = column.matcher(parts[2]);
            if (!c.find()) continue;
            List<String> values = new ArrayList<>();
            Matcher v = value.matcher(parts[2]);
            while (v.find()) values.add(v.group(1));
            result.put(database + "|" + parts[0].strip() + "|" + c.group(1), new Constraint(parts[1].strip(), values, snapshot.getFileName().toString()));
        }
        return result;
    }

    private static Map<String, Constraint> readMigrations() throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(MIGRATIONS)) {
            files = list.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        Pattern definition = Pattern.compile("ADD\\s+CONSTRAINT\\s+(\\w+)\\s+CHECK\\s*\\(\\s*\\(?\\s*(\\w+)\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern value = Pattern.compile("'([A-Z0-9_]+)'");
        Map<String, Constraint> latest = new LinkedHashMap<>();
        for (Path file : files) {
            // commented-out statements (the ROLLBACK blocks) are not definitions
            String sql = String.join("\n", Files.readAllLines(file).stream().filter(l -> !l.strip().startsWith("--")).toList());
            Matcher m = definition.matcher(sql);
            while (m.find()) {
                List<String> values = new ArrayList<>();
                Matcher v = value.matcher(m.group(3));
                while (v.find()) values.add(v.group(1));
                latest.put(m.group(1), new Constraint(m.group(1), values, file.getFileName().toString()));
            }
        }
        return latest;
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
