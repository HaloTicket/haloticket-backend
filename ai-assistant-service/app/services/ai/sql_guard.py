"""Lớp an toàn cho SQL do mô hình ngôn ngữ viết.

Văn bản của mô hình không bao giờ được chạy nguyên trạng. Nó được phân tích bằng sqlglot (phương ngữ PostgreSQL) và bị từ chối
trừ khi đúng là một câu truy vấn chỉ đọc, chỉ chạm các view hoặc bảng được phép, chỉ gọi hàm vô hại và không có chú thích.
Thứ được chạy là SQL sinh lại từ cây cú pháp đã kiểm tra, với LIMIT tối đa `max_rows`.

Đây là tuyến phòng thủ thứ hai. Tuyến thứ nhất là tài khoản cơ sở dữ liệu mà phần phân tích kết nối (role chỉ đọc các view
phân tích), nên một sai sót ở đây vẫn không thể chạm dữ liệu ngoài các view đó.
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

# các hàm mà truy vấn sinh ra được phép gọi dù sqlglot không biết chúng như hàm dựng sẵn. Mọi hàm khác không phải
# hàm tổng hợp / hàm vô hướng dựng sẵn mà sqlglot biết đều bị từ chối.
SAFE_UNKNOWN_FUNCTIONS = frozenset({
    "now", "date_part", "to_char", "to_date", "age", "initcap", "btrim", "ltrim", "rtrim", "split_part",
    "regexp_replace", "date_bin", "make_date", "isfinite", "justify_days",
})

# bị từ chối dù sqlglot biết chúng: chúng đọc tệp, gọi máy chủ khác, đổi cấu hình hoặc ngốn CPU.
FORBIDDEN_FUNCTIONS = frozenset({
    "set_config", "current_setting", "dblink", "dblink_exec", "query_to_xml", "xpath", "version",
    "generate_series", "exploding_generate_series", "unnest", "pg_sleep", "txid_current", "lo_import", "lo_export",
    "copy", "nextval", "setval", "currval", "lastval",
    # sqlglot biết các hàm này dưới tên riêng; chúng làm lộ thông tin máy chủ hoặc nở ra nhiều dòng
    "current_version", "current_database", "current_catalog", "current_schema", "current_schemas", "current_user",
    "session_user", "explode", "inet_server_addr", "inet_client_addr",
})
FORBIDDEN_FUNCTION_PREFIXES = ("pg_", "lo_", "dblink", "xpath", "query_to_", "cursor_to_", "database_to_", "schema_to_", "table_to_")


class SqlGuardError(Exception):
    """Truy vấn bị từ chối. `code` là lý do ổn định cho máy đọc, `message` an toàn để hiển thị cho người dùng."""

    def __init__(self, code: str, message: str):
        """Lưu mã lý do và thông báo tiếng Việt cho người dùng."""
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class GuardedSql:
    """Câu SQL đã được kiểm tra và sinh lại, cùng giá trị LIMIT thực tế được áp dụng."""
    sql: str
    limit: int


@dataclass(frozen=True)
class ScopeSpec:
    """Giới hạn truy vấn trong một số sự kiện. `columns` ánh xạ một relation tới cột chứa id sự kiện; mọi chỗ dùng relation
    đó được thay bằng một truy vấn con chỉ giữ các dòng thuộc `event_ids` (không dòng nào khi tuple rỗng).
    Relation `public` không có id sự kiện và được đọc nguyên trạng. Thứ gì khác đều bị từ chối."""
    columns: dict
    public: frozenset
    event_ids: tuple


def fingerprint(sql: str) -> str:
    """Băm ngắn để ghi log: cho phép đối chiếu một lần từ chối với câu truy vấn mà không ghi câu truy vấn (có thể chứa dữ liệu cá nhân)."""
    return hashlib.sha256((sql or "").encode("utf-8")).hexdigest()[:12]


def parse_allowed(csv: str) -> frozenset:
    """Tách chuỗi phân cách bằng dấu phẩy thành tập tên relation viết thường."""
    return frozenset(item.strip().lower() for item in (csv or "").split(",") if item.strip())


def validate_sql(raw_sql: str, allowed_relations: Iterable[str], schema: Optional[str] = None, max_rows: int = 500,
                 scope: Optional[ScopeSpec] = None) -> GuardedSql:
    """Kiểm tra SQL do mô hình sinh và trả GuardedSql đã sinh lại; ném SqlGuardError khi bị từ chối (và ghi log kèm băm của câu truy vấn).

    Args:
        raw_sql: văn bản SQL của mô hình.
        allowed_relations: các view/bảng được phép.
        schema: schema duy nhất được phép ghi kèm tên bảng.
        max_rows: LIMIT tối đa.
        scope: giới hạn theo sự kiện của người gọi (None nếu không giới hạn).
    """
    allowed = frozenset(name.lower() for name in allowed_relations)
    schema = (schema or "").lower() or None
    if scope is not None:
        # người gọi bị giới hạn phạm vi không bao giờ được nhiều hơn danh sách cho phép đã cấu hình, và chỉ các relation mà phạm vi biết cách giới hạn
        allowed = allowed & (frozenset(scope.columns) | scope.public)
    try:
        return _validate(raw_sql, allowed, schema, max_rows, scope)
    except SqlGuardError as refusal:
        log.warning("SQL refused: code=%s sql=%s chars=%d", refusal.code, fingerprint(raw_sql), len(raw_sql or ""))
        raise


def _validate(raw_sql: str, allowed: frozenset, schema: Optional[str], max_rows: int, scope: Optional[ScopeSpec] = None) -> GuardedSql:
    """Thực hiện các bước kiểm tra: độ dài, chú thích và dấu chấm phẩy, đúng một câu SELECT, relation, hàm, phạm vi sự kiện rồi ép LIMIT."""
    sql = (raw_sql or "").strip()
    if not sql:
        raise SqlGuardError("EMPTY_SQL", "Không có câu truy vấn SQL để thực thi.")
    if len(sql) > MAX_SQL_CHARS:
        raise SqlGuardError("SQL_TOO_LONG", "Câu truy vấn SQL quá dài.")
    if not allowed:
        raise SqlGuardError("NO_ALLOWED_RELATIONS", "Chưa cấu hình danh sách bảng/view được phép truy vấn.")

    # chú thích và dấu chấm phẩy được đọc từ các token, nên những cái nằm trong chuỗi ký tự không bị tính
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
    """Từ chối hàm làm nguồn dữ liệu, bảng có catalog, schema lạ và bảng ngoài danh sách cho phép (tên CTE được bỏ qua)."""
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
    """Thay mỗi chỗ dùng relation có phạm vi bằng truy vấn con lọc theo id sự kiện của người gọi; từ chối nếu CTE trùng tên view hoặc còn chỗ chưa được lọc."""
    cte_names = {cte.alias_or_name.lower() for cte in tree.find_all(exp.CTE)}
    if cte_names & set(scope.columns):
        # trong thân của chính nó, tên CTE vẫn có nghĩa là relation thật; từ chối thì đơn giản hơn là phân biệt hai trường hợp
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
        # copy=False giữ `base` đúng là nút nằm trong cây, điều mà bước kiểm tra bên dưới dựa vào
        table.replace(exp.select("*").from_(base, copy=False).where(condition, copy=False).subquery(alias, copy=False))
    # mọi chỗ dùng relation có phạm vi giờ phải là một trong các truy vấn con đã lọc ở trên
    for table in tree.find_all(exp.Table):
        if table.name.lower() in scope.columns and not any(table is base for base in inner):
            raise SqlGuardError("RELATION_NOT_ALLOWED", "Không thể giới hạn phạm vi dữ liệu cho câu truy vấn này.")


def _check_functions(tree: exp.Expression) -> None:
    """Từ chối hàm bị cấm (danh sách, tiền tố) và hàm lạ không nằm trong SAFE_UNKNOWN_FUNCTIONS."""
    for func in tree.find_all(exp.Func):
        name = (func.name if isinstance(func, exp.Anonymous) else func.sql_name()).lower()
        if name in FORBIDDEN_FUNCTIONS or name.startswith(FORBIDDEN_FUNCTION_PREFIXES):
            raise SqlGuardError("FUNCTION_NOT_ALLOWED", f"Không được phép dùng hàm '{name}'.")
        if isinstance(func, exp.Anonymous) and name not in SAFE_UNKNOWN_FUNCTIONS:
            raise SqlGuardError("FUNCTION_NOT_ALLOWED", f"Không được phép dùng hàm '{name}'.")


def _enforce_limit(tree: exp.Expression, max_rows: int) -> int:
    """Giữ LIMIT số nguyên trong khoảng 1..max_rows; nếu thiếu hoặc vượt thì đặt thành max_rows. Trả về LIMIT thực tế."""
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
