"""Safety layer for SQL written by a language model.

The model's text is never executed as is. It is parsed with sqlglot (PostgreSQL dialect) and refused unless it is exactly
one read-only query that touches only the allowed views or tables, calls only harmless functions and contains no
comments. What is executed is the SQL regenerated from the checked syntax tree, with a LIMIT of at most `max_rows`.

This is the second line of defence. The first is the database account the analysis connects with (a role that can only
read the analysis views), so a mistake here still cannot reach data outside them.
"""
import hashlib
import logging
from dataclasses import dataclass
from typing import Iterable, Optional

import sqlglot
from sqlglot import exp
from sqlglot.dialects.dialect import Dialect
from sqlglot.errors import SqlglotError

log = logging.getLogger("ai.sql_guard")

_DIALECT = "postgres"
MAX_SQL_CHARS = 5000

# functions a generated query may call when sqlglot does not know them as built-ins. Everything else that is not a
# built-in aggregate / scalar function known to sqlglot is refused.
SAFE_UNKNOWN_FUNCTIONS = frozenset({
    "now", "date_part", "to_char", "to_date", "age", "initcap", "btrim", "ltrim", "rtrim", "split_part",
    "regexp_replace", "date_bin", "make_date", "isfinite", "justify_days",
})

# refused even if sqlglot knows them: they read files, call other servers, change settings or burn CPU.
FORBIDDEN_FUNCTIONS = frozenset({
    "set_config", "current_setting", "dblink", "dblink_exec", "query_to_xml", "xpath", "version",
    "generate_series", "exploding_generate_series", "unnest", "pg_sleep", "txid_current", "lo_import", "lo_export",
    "copy", "nextval", "setval", "currval", "lastval",
    # sqlglot knows these under its own names; they reveal the server or expand into many rows
    "current_version", "current_database", "current_catalog", "current_schema", "current_schemas", "current_user",
    "session_user", "explode", "inet_server_addr", "inet_client_addr",
})
FORBIDDEN_FUNCTION_PREFIXES = ("pg_", "lo_", "dblink", "xpath", "query_to_", "cursor_to_", "database_to_", "schema_to_", "table_to_")


class SqlGuardError(Exception):
    """The query was refused. `code` is a stable machine-readable reason, `message` is safe to show to the user."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class GuardedSql:
    sql: str
    limit: int


@dataclass(frozen=True)
class ScopeSpec:
    """Limits a query to some events. `columns` maps a relation to the column that holds the event id; every use of such a
    relation is replaced by a subquery that keeps only the rows of `event_ids` (none at all when the tuple is empty).
    `public` relations carry no event id and are readable as they are. Anything else is refused."""
    columns: dict
    public: frozenset
    event_ids: tuple


def fingerprint(sql: str) -> str:
    """Short hash for logs: lets a refusal be matched to a query without writing the query (it may hold personal data)."""
    return hashlib.sha256((sql or "").encode("utf-8")).hexdigest()[:12]


def parse_allowed(csv: str) -> frozenset:
    return frozenset(item.strip().lower() for item in (csv or "").split(",") if item.strip())


def validate_sql(raw_sql: str, allowed_relations: Iterable[str], schema: Optional[str] = None, max_rows: int = 500,
                 scope: Optional[ScopeSpec] = None) -> GuardedSql:
    allowed = frozenset(name.lower() for name in allowed_relations)
    schema = (schema or "").lower() or None
    if scope is not None:
        # a scoped caller never gets more than the configured allow-list, and only relations the scope knows how to limit
        allowed = allowed & (frozenset(scope.columns) | scope.public)
    try:
        return _validate(raw_sql, allowed, schema, max_rows, scope)
    except SqlGuardError as refusal:
        log.warning("SQL refused: code=%s sql=%s chars=%d", refusal.code, fingerprint(raw_sql), len(raw_sql or ""))
        raise


def _validate(raw_sql: str, allowed: frozenset, schema: Optional[str], max_rows: int, scope: Optional[ScopeSpec] = None) -> GuardedSql:
    sql = (raw_sql or "").strip()
    if not sql:
        raise SqlGuardError("EMPTY_SQL", "Không có câu truy vấn SQL để thực thi.")
    if len(sql) > MAX_SQL_CHARS:
        raise SqlGuardError("SQL_TOO_LONG", "Câu truy vấn SQL quá dài.")
    if not allowed:
        raise SqlGuardError("NO_ALLOWED_RELATIONS", "Chưa cấu hình danh sách bảng/view được phép truy vấn.")

    # comments and semicolons are read from the tokens, so ones inside string literals do not count
    try:
        tokens = Dialect.get_or_raise(_DIALECT).tokenize(sql)
    except SqlglotError:
        raise SqlGuardError("INVALID_SQL", "Câu truy vấn SQL không hợp lệ.")
    if any(token.comments for token in tokens):
        raise SqlGuardError("COMMENT_NOT_ALLOWED", "Không được phép dùng chú thích (comment) trong câu truy vấn SQL.")
    semicolons = [index for index, token in enumerate(tokens) if token.token_type.name == "SEMICOLON"]
    if semicolons and semicolons != [len(tokens) - 1]:
        raise SqlGuardError("MULTIPLE_STATEMENTS", "Không được phép thực thi nhiều câu truy vấn SQL cùng lúc.")

    try:
        statements = [tree for tree in sqlglot.parse(sql, read=_DIALECT) if tree is not None]
    except SqlglotError:
        raise SqlGuardError("INVALID_SQL", "Câu truy vấn SQL không hợp lệ.")
    if len(statements) != 1:
        raise SqlGuardError("MULTIPLE_STATEMENTS" if statements else "EMPTY_SQL", "Chỉ cho phép đúng một câu truy vấn SQL.")
    tree = statements[0]

    if not isinstance(tree, (exp.Select, exp.SetOperation)):
        raise SqlGuardError("NOT_A_SELECT", "Chỉ cho phép truy vấn đọc dữ liệu (SELECT).")
    if tree.find(exp.Into):
        raise SqlGuardError("NOT_A_SELECT", "Không được phép dùng SELECT ... INTO.")
    if tree.find(exp.Lock):
        raise SqlGuardError("NOT_A_SELECT", "Không được phép khóa dòng dữ liệu (FOR UPDATE/SHARE).")
    for node in tree.walk():
        if isinstance(node, (exp.Insert, exp.Update, exp.Delete, exp.Drop, exp.Create, exp.Alter, exp.Command, exp.Copy,
                             exp.Set, exp.Merge, exp.Use, exp.Transaction, exp.Commit, exp.Rollback)):
            raise SqlGuardError("NOT_A_SELECT", "Chỉ cho phép truy vấn đọc dữ liệu (SELECT).")

    _check_relations(tree, allowed, schema)
    _check_functions(tree)
    if scope is not None:
        _apply_scope(tree, scope)
    limit = _enforce_limit(tree, max_rows)
    return GuardedSql(sql=tree.sql(dialect=_DIALECT), limit=limit)


def _check_relations(tree: exp.Expression, allowed: frozenset, schema: Optional[str]) -> None:
    cte_names = {cte.alias_or_name.lower() for cte in tree.find_all(exp.CTE)}
    for table in tree.find_all(exp.Table):
        if not isinstance(table.this, exp.Identifier):
            raise SqlGuardError("FUNCTION_AS_TABLE", "Không được phép dùng hàm làm nguồn dữ liệu.")
        if table.catalog:
            raise SqlGuardError("RELATION_NOT_ALLOWED", "Không được phép truy vấn bảng ngoài danh sách cho phép.")
        name = table.name.lower()
        namespace = (table.db or "").lower()
        if namespace and namespace != schema:
            raise SqlGuardError("RELATION_NOT_ALLOWED", f"Không được phép truy vấn bảng '{table.db}.{table.name}'.")
        if not namespace and name in cte_names:
            continue
        if name not in allowed:
            raise SqlGuardError("RELATION_NOT_ALLOWED", f"Không được phép truy vấn bảng '{table.name}'.")


def _apply_scope(tree: exp.Expression, scope: ScopeSpec) -> None:
    cte_names = {cte.alias_or_name.lower() for cte in tree.find_all(exp.CTE)}
    if cte_names & set(scope.columns):
        # inside its own body a CTE name still means the real relation; refusing is simpler than telling the two apart
        raise SqlGuardError("RELATION_NOT_ALLOWED", "Không được đặt tên CTE trùng với tên view dữ liệu.")
    targets = [(table, scope.columns[table.name.lower()]) for table in tree.find_all(exp.Table)
               if table.name.lower() in scope.columns]
    inner = []
    for table, column in targets:
        alias = table.alias or table.name
        base = exp.Table(this=exp.to_identifier(table.name), db=exp.to_identifier(table.db) if table.db else None)
        if scope.event_ids:
            condition = exp.In(this=exp.column(column), expressions=[exp.Literal.string(event_id) for event_id in scope.event_ids])
        else:
            condition = exp.Boolean(this=False)
        inner.append(base)
        # copy=False keeps `base` the very node that ends up in the tree, which the check below relies on
        table.replace(exp.select("*").from_(base, copy=False).where(condition, copy=False).subquery(alias, copy=False))
    # every use of a scoped relation must now be one of the filtered ones built above
    for table in tree.find_all(exp.Table):
        if table.name.lower() in scope.columns and not any(table is base for base in inner):
            raise SqlGuardError("RELATION_NOT_ALLOWED", "Không thể giới hạn phạm vi dữ liệu cho câu truy vấn này.")


def _check_functions(tree: exp.Expression) -> None:
    for func in tree.find_all(exp.Func):
        name = (func.name if isinstance(func, exp.Anonymous) else func.sql_name()).lower()
        if name in FORBIDDEN_FUNCTIONS or name.startswith(FORBIDDEN_FUNCTION_PREFIXES):
            raise SqlGuardError("FUNCTION_NOT_ALLOWED", f"Không được phép dùng hàm '{name}'.")
        if isinstance(func, exp.Anonymous) and name not in SAFE_UNKNOWN_FUNCTIONS:
            raise SqlGuardError("FUNCTION_NOT_ALLOWED", f"Không được phép dùng hàm '{name}'.")


def _enforce_limit(tree: exp.Expression, max_rows: int) -> int:
    current = tree.args.get("limit")
    value = None
    if current is not None:
        expression = current.args.get("expression")
        if isinstance(expression, exp.Literal) and expression.is_int:
            value = int(expression.this)
    if value is None or value < 1 or value > max_rows:
        tree.set("limit", exp.Limit(expression=exp.Literal.number(max_rows)))
        value = max_rows
    return value
