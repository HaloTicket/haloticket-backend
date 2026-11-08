"""Fake read-only chatbot engines for the customer tests: no network, no database, no model."""
from types import SimpleNamespace


class FakeResult:
    def __init__(self, rows):
        self._rows = list(rows)

    def fetchall(self):
        return list(self._rows)

    def fetchone(self):
        return self._rows[0] if self._rows else None


class FakeConn:
    def __init__(self, kind, handlers, statements, failure=None):
        self.kind, self.handlers, self.statements, self.failure = kind, handlers, statements, failure

    def execute(self, statement, params=None):
        sql = str(statement)
        self.statements.append(SimpleNamespace(kind=self.kind, sql=sql, params=params or {}))
        if self.failure is not None:
            raise self.failure
        for needle, rows in self.handlers:
            if needle in sql:
                return FakeResult(rows(params or {}) if callable(rows) else rows)
        return FakeResult([])

    def rollback(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeEngine:
    def __init__(self, kind, handlers, statements, failure=None, connect_failure=None):
        self.kind, self.handlers, self.statements = kind, handlers, statements
        self.failure, self.connect_failure = failure, connect_failure

    def connect(self):
        if self.connect_failure is not None:
            raise self.connect_failure
        return FakeConn(self.kind, self.handlers, self.statements, self.failure)


class Engines:
    """get_chatbot_engine replacement; remembers every statement with the kind of engine that ran it."""

    def __init__(self, event=None, ticket=None, failure=None):
        self.statements = []
        self.kinds_requested = []
        self.event = event or []
        self.ticket = ticket or []
        self.failure = failure

    def __call__(self, kind):
        self.kinds_requested.append(kind)
        return FakeEngine(kind, self.event if kind == "event" else self.ticket, self.statements, self.failure)

    def sql_of(self, kind):
        return [s.sql for s in self.statements if s.kind == kind]


def row(**kw):
    return SimpleNamespace(**kw)


class FakeMessage:
    """What ai_invoke returns."""

    def __init__(self, content="", tool_calls=None):
        self.content = content
        self.tool_calls = tool_calls or []
