"""The safety layer for SQL written by the model: fixed lists of acceptable and unacceptable queries."""
import pytest

from app.services.ai.sql_guard import SqlGuardError, parse_allowed, validate_sql

ALLOWED = ["v_events", "v_ticket_types", "v_ticket_sales_daily"]
SCHEMA = "ai_analytics"


def check(sql, **kwargs):
    return validate_sql(sql, kwargs.pop("allowed", ALLOWED), schema=kwargs.pop("schema", SCHEMA), max_rows=kwargs.pop("max_rows", 500))


def refused_code(sql, **kwargs):
    with pytest.raises(SqlGuardError) as refusal:
        check(sql, **kwargs)
    return refusal.value.code


# --- accepted ---------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("sql", [
    "SELECT count(*) FROM v_events",
    "select title, city from v_events where city = 'Hà Nội' order by start_time desc limit 20",
    "SELECT ai_analytics.v_events.title FROM ai_analytics.v_events",
    "SELECT e.title, sum(s.tickets_sold) AS sold FROM v_events e JOIN v_ticket_sales_daily s ON s.event_id = e.event_id GROUP BY e.title",
    "WITH recent AS (SELECT event_id FROM v_events WHERE start_time > now()) SELECT count(*) FROM recent",
    "SELECT date_trunc('day', sale_date) AS d, sum(revenue) FROM v_ticket_sales_daily GROUP BY 1 ORDER BY 1",
    "SELECT event_id FROM v_events WHERE event_id IN (SELECT event_id FROM v_ticket_types WHERE price < 500000)",
    "SELECT title FROM v_events UNION SELECT name FROM v_ticket_types",
    "SELECT 'a--b' AS dashes, 'x;y' AS semi, 'p:q' AS colon FROM v_events",
    "SELECT count(*) FROM v_events;",
    "SELECT coalesce(max(price), 0), round(avg(price), 2) FROM v_ticket_types",
])
def test_valid_queries_are_accepted(sql):
    guarded = check(sql)
    assert guarded.sql.upper().startswith(("SELECT", "WITH"))
    assert 1 <= guarded.limit <= 500
    assert "LIMIT" in guarded.sql.upper()


def test_the_executed_text_is_regenerated_from_the_checked_tree_not_the_original():
    guarded = check("select  COUNT( * )   from   v_events;")
    assert guarded.sql == "SELECT COUNT(*) FROM v_events LIMIT 500"


# --- LIMIT ------------------------------------------------------------------------------------------------------------

def test_a_missing_limit_is_added():
    guarded = check("SELECT title FROM v_events")
    assert guarded.limit == 500 and guarded.sql.endswith("LIMIT 500")


def test_a_limit_above_the_maximum_is_lowered():
    guarded = check("SELECT title FROM v_events LIMIT 100000")
    assert guarded.limit == 500 and guarded.sql.endswith("LIMIT 500")


def test_a_limit_within_the_maximum_is_kept():
    assert check("SELECT title FROM v_events LIMIT 25").limit == 25


def test_the_maximum_comes_from_the_caller():
    assert check("SELECT title FROM v_events LIMIT 400", max_rows=100).limit == 100


def test_a_limit_that_is_not_a_plain_number_is_replaced():
    assert check("SELECT title FROM v_events LIMIT (SELECT 5)").limit == 500


def test_a_union_gets_one_limit_on_the_whole_query():
    guarded = check("SELECT title FROM v_events UNION ALL SELECT name FROM v_ticket_types")
    assert guarded.sql.endswith("LIMIT 500")


# --- statements that write or are not a single SELECT -------------------------------------------------------------------

@pytest.mark.parametrize("sql", [
    "INSERT INTO v_events (title) VALUES ('x')",
    "UPDATE v_events SET title = 'x'",
    "DELETE FROM v_events",
    "DROP TABLE v_events",
    "TRUNCATE v_events",
    "ALTER TABLE v_events ADD COLUMN a int",
    "CREATE TABLE t AS SELECT * FROM v_events",
    "GRANT ALL ON v_events TO public",
    "COPY v_events TO '/tmp/out.csv'",
    "SET statement_timeout = 0",
    "EXPLAIN SELECT * FROM v_events",
    "SELECT * INTO leaked FROM v_events",
    "SELECT * FROM v_events FOR UPDATE",
    "WITH d AS (DELETE FROM v_events RETURNING *) SELECT * FROM d",
    "BEGIN",
])
def test_anything_that_is_not_a_plain_select_is_refused(sql):
    assert refused_code(sql) == "NOT_A_SELECT"


@pytest.mark.parametrize("sql", [
    "SELECT 1 FROM v_events; SELECT 2 FROM v_events",
    "SELECT 1 FROM v_events; DROP TABLE v_events",
    "SELECT 1 FROM v_events;; ",
    ";SELECT 1 FROM v_events",
])
def test_more_than_one_statement_is_refused(sql):
    assert refused_code(sql) == "MULTIPLE_STATEMENTS"


@pytest.mark.parametrize("sql", ["", "   ", ";"])
def test_an_empty_query_is_refused(sql):
    assert refused_code(sql) == "EMPTY_SQL"


def test_a_query_that_does_not_parse_is_refused():
    assert refused_code("SELEC title FRM v_events") in ("INVALID_SQL", "NOT_A_SELECT", "RELATION_NOT_ALLOWED")


def test_a_very_long_query_is_refused():
    assert refused_code("SELECT " + "1," * 4000 + "1 FROM v_events") == "SQL_TOO_LONG"


# --- comments ---------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("sql", [
    "SELECT title FROM v_events -- hide this",
    "SELECT /* hint */ title FROM v_events",
    "SELECT title FROM v_events /* trailing */",
    "SELECT title FROM v_events WHERE 1=1 --\n AND city = 'x'",
])
def test_sql_comments_are_refused(sql):
    assert refused_code(sql) == "COMMENT_NOT_ALLOWED"


# --- relations --------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("sql", [
    "SELECT * FROM users",
    "SELECT * FROM events",
    "SELECT * FROM bookings",
    "SELECT * FROM staff_assignments",
    "SELECT * FROM public.events",
    "SELECT * FROM pg_catalog.pg_user",
    "SELECT * FROM pg_authid",
    "SELECT * FROM information_schema.tables",
    "SELECT * FROM otherdb.ai_analytics.v_events",
    "SELECT * FROM other_schema.v_events",
])
def test_tables_outside_the_allow_list_are_refused(sql):
    assert refused_code(sql) == "RELATION_NOT_ALLOWED"


def test_a_union_into_a_forbidden_table_is_refused():
    assert refused_code("SELECT title FROM v_events UNION SELECT email FROM users") == "RELATION_NOT_ALLOWED"


def test_a_subquery_into_a_forbidden_table_is_refused():
    assert refused_code("SELECT title FROM v_events WHERE event_id IN (SELECT event_id FROM staff_assignments)") == "RELATION_NOT_ALLOWED"
    assert refused_code("SELECT (SELECT count(*) FROM users) AS n FROM v_events") == "RELATION_NOT_ALLOWED"
    assert refused_code("SELECT * FROM (SELECT * FROM bookings) b") == "RELATION_NOT_ALLOWED"


def test_a_cte_cannot_hide_a_forbidden_table():
    assert refused_code("WITH a AS (SELECT * FROM users) SELECT * FROM a") == "RELATION_NOT_ALLOWED"


def test_a_cte_name_does_not_shadow_a_schema_qualified_table():
    assert refused_code("WITH v_events AS (SELECT 1) SELECT * FROM public.v_events") == "RELATION_NOT_ALLOWED"


def test_names_are_compared_without_regard_to_case_and_quoting():
    assert check('SELECT * FROM "V_EVENTS"').limit == 500
    assert refused_code('SELECT * FROM "Users"') == "RELATION_NOT_ALLOWED"


def test_a_function_cannot_be_used_as_a_data_source():
    assert refused_code("SELECT * FROM generate_series(1, 10)") == "FUNCTION_AS_TABLE"
    assert refused_code("SELECT * FROM pg_read_file('/etc/passwd')") == "FUNCTION_AS_TABLE"
    assert refused_code("SELECT * FROM dblink('host=x', 'select 1') AS t(a int)") == "FUNCTION_AS_TABLE"


def test_nothing_is_allowed_when_the_allow_list_is_empty():
    assert refused_code("SELECT 1", allowed=[]) == "NO_ALLOWED_RELATIONS"


def test_without_a_schema_only_unqualified_names_are_accepted():
    assert check("SELECT * FROM v_events", schema=None).limit == 500
    assert refused_code("SELECT * FROM public.v_events", schema=None) == "RELATION_NOT_ALLOWED"


# --- functions --------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("sql", [
    "SELECT pg_read_file('/etc/passwd') FROM v_events",
    "SELECT pg_ls_dir('/') FROM v_events",
    "SELECT pg_read_binary_file('/etc/passwd')",
    "SELECT pg_sleep(30)",
    "SELECT pg_terminate_backend(1)",
    "SELECT lo_import('/etc/passwd')",
    "SELECT lo_export(1, '/tmp/x')",
    "SELECT dblink('host=evil', 'select 1')",
    "SELECT dblink_exec('host=evil', 'drop table x')",
    "SELECT set_config('statement_timeout', '0', false)",
    "SELECT current_setting('server_version')",
    "SELECT version()",
    "SELECT query_to_xml('select * from users', true, true, '')",
    "SELECT nextval('seq')",
    "SELECT generate_series(1, 100000000)",
    "SELECT unnest(ARRAY[1, 2, 3])",
    "SELECT current_database()",
])
def test_dangerous_functions_are_refused(sql):
    assert refused_code(sql) == "FUNCTION_NOT_ALLOWED"


def test_a_dangerous_function_is_refused_wherever_it_is_hidden():
    assert refused_code("SELECT title FROM v_events WHERE title = pg_read_file('x')") == "FUNCTION_NOT_ALLOWED"
    assert refused_code("SELECT title FROM v_events ORDER BY pg_sleep(1)") == "FUNCTION_NOT_ALLOWED"
    assert refused_code("WITH a AS (SELECT pg_read_file('x') AS f) SELECT f FROM a") == "FUNCTION_NOT_ALLOWED"
    assert refused_code("SELECT title FROM v_events WHERE event_id IN (SELECT lo_import('x')::text)") == "FUNCTION_NOT_ALLOWED"


# configuration 
def test_the_allow_list_is_read_from_a_comma_separated_setting():
    assert parse_allowed(" v_events , V_TICKET_TYPES,, ") == frozenset({"v_events", "v_ticket_types"})
    assert parse_allowed("") == frozenset()
