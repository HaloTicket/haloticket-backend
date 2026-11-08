"""In-memory stand-ins for the admin intelligence storage and the upstream readers (no database, no network, no model)."""
import itertools
import time
from datetime import datetime, timezone
from types import SimpleNamespace

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.endpoints import admin
from app.core.config import settings
from app.core.errors import install_error_handlers
from app.core.security import TokenVerifier, get_verifier
from app.services.admin_intel import store, upstream

ISSUER = "https://login.example.test/realms/haloticket"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def admin_token(roles=("ROLE_ADMIN",), sub="admin-1"):
    pem = KEY.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return jwt.encode({"iss": ISSUER, "sub": sub, "roles": list(roles), "exp": int(time.time()) + 300}, pem, algorithm="RS256")


def make_client(sub="admin-1", roles=("ROLE_ADMIN",)) -> TestClient:
    app = FastAPI()
    install_error_handlers(app)
    app.include_router(admin.router, prefix="/api/admin")
    app.dependency_overrides[get_verifier] = lambda: TokenVerifier(ISSUER, lambda _t: KEY.public_key())
    client = TestClient(app)
    client.headers.update({"Authorization": "Bearer " + admin_token(roles, sub)})
    return client


def event(event_id="e1", title="Đêm nhạc mùa thu", description="Một đêm nhạc acoustic ấm áp", status="PENDING", organizer_user="u1",
          min_price=300000, category="Âm nhạc", submitted="2026-11-08T00:00:00", **extra):
    base = {"id": event_id, "title": title, "description": description, "descriptionText": description,
            "eventApprovalStatus": status, "eventStatus": "PENDING" if status == "PENDING" else status,
            "organizer": {"id": "o-" + organizer_user, "userId": organizer_user, "name": "Ban tổ chức A"},
            "minPrice": min_price, "categoryName": category, "locationName": "Nhà hát", "address": "1 Đường A", "locationType": "OFFLINE",
            "startTime": "2027-01-01T19:00:00", "endTime": "2027-01-01T22:00:00", "submittedAt": submitted,
            "tickets": [{"name": "Thường", "price": min_price, "totalQuantity": 100}]}
    base.update(extra)
    return base


class FakeStore:
    """Replaces the functions of app.services.admin_intel.store with lists in memory."""

    def __init__(self, monkeypatch):
        self.assessments, self.decisions, self.pins, self.summaries = [], [], {}, {}
        self.ids = itertools.count(1)
        monkeypatch.setattr(settings, "ai_admin_database_url", "postgresql+psycopg2://fake")
        for name in ("require_engine", "latest_assessment", "latest_assessments", "assessment_history", "save_assessment", "save_decision",
                     "decisions_since", "assessed_since", "count_pins", "list_pins", "create_pin", "update_pin_snapshot", "get_pin",
                     "delete_pin", "get_summary", "put_summary"):
            monkeypatch.setattr(store, name, getattr(self, "_" + name))

    def _require_engine(self):
        return object()

    def _row(self, a):
        return {"assessment_id": a["id"], "event_id": a["event_id"], "content_hash": a["hash"], "ruleset_version": a["result"]["ruleset_version"] or "",
                "risk_score": a["result"]["risk_score"], "risk_level": a["result"]["risk_level"], "complete": a["result"]["complete"],
                "reason_items": a["result"]["reason_items"], "coverage": a["result"]["coverage"], "ai_layer": a["result"]["coverage"]["ai"],
                "trigger": a["trigger"], "assessed_at": datetime.now(timezone.utc).isoformat()}

    def _latest_assessment(self, event_id):
        rows = [a for a in self.assessments if a["event_id"] == event_id]
        return self._row(rows[-1]) if rows else None

    def _latest_assessments(self, ids):
        return {i: self._latest_assessment(i) for i in ids if self._latest_assessment(i)}

    def _assessment_history(self, event_id, limit=10):
        return [self._row(a) for a in reversed([a for a in self.assessments if a["event_id"] == event_id])][:limit]

    def _save_assessment(self, event_id, result, content_hash, trigger, created_by):
        entry = {"id": next(self.ids), "event_id": event_id, "result": result, "hash": content_hash, "trigger": trigger, "by": created_by}
        self.assessments.append(entry)
        return entry["id"]

    def _save_decision(self, event_id, decision, reason, decided_by, source):
        self.decisions.append({"event_id": event_id, "decision": decision, "reason": reason, "decided_by": decided_by, "source": source})
        return {"id": len(self.decisions), "duplicate": False}

    def _decisions_since(self, days):
        latest = {}
        for d in self.decisions:
            latest[d["event_id"]] = d
        return list(latest.values())

    def _assessed_since(self, days):
        latest = {}
        for a in self.assessments:
            latest[a["event_id"]] = a
        return [{"event_id": k, "level": v["result"]["risk_level"], "complete": v["result"]["complete"]} for k, v in latest.items()]

    def _count_pins(self, admin_sub):
        return sum(1 for p in self.pins.values() if p["admin"] == admin_sub)

    def _list_pins(self, admin_sub):
        return [dict(p, pin_id=k) for k, p in self.pins.items() if p["admin"] == admin_sub]

    def _create_pin(self, admin_sub, title, question, sql_text, chart_type, snapshot):
        pin_id = f"pin{next(self.ids)}"
        self.pins[pin_id] = {"admin": admin_sub, "title": title, "question": question, "sql": sql_text, "chart_type": chart_type,
                             "snapshot": snapshot, "created_at": "2026-11-08T00:00:00+00:00"}
        return pin_id

    def _update_pin_snapshot(self, admin_sub, pin_id, snapshot):
        self.pins[pin_id]["snapshot"] = snapshot

    def _get_pin(self, admin_sub, pin_id):
        pin = self.pins.get(pin_id)
        return dict(pin, pin_id=pin_id) if pin and pin["admin"] == admin_sub else None

    def _delete_pin(self, admin_sub, pin_id):
        pin = self.pins.get(pin_id)
        if pin and pin["admin"] == admin_sub:
            del self.pins[pin_id]
            return True
        return False

    def _get_summary(self, day, lang):
        return self.summaries.get((day, lang))

    def _put_summary(self, day, lang, text, kpi_hash):
        self.summaries[(day, lang)] = {"summary": text, "kpi_hash": kpi_hash, "created_at": "2026-11-08T00:00:00+00:00"}


class FakeUpstream:
    """Replaces the readers of app.services.admin_intel.upstream. A reader set to an Exception raises it."""

    def __init__(self, monkeypatch, events=None):
        self.values = {
            "approvals": events if events is not None else [], "user": {"createdAt": "2020-01-01T00:00:00Z", "ekycStatus": "APPROVED"},
            "category_prices": [100000, 200000, 300000, 400000, 500000], "reconciliation_summary": {"openExceptions": 0, "paidWithoutTickets": 0, "stockDiscrepancies": 0},
            "reconciliation_exceptions": {"items": []}, "paid_without_tickets": {"items": []},
            "sales_timeline": {"timeline": []}, "resale_summary": {"pendingCount": 0, "oldestPendingAt": None},
        }
        self.calls = []
        monkeypatch.setattr(upstream, "approvals", lambda auth, fresh=False: self._get("approvals"))
        monkeypatch.setattr(upstream, "user", lambda auth, user_id: self._get("user"))
        monkeypatch.setattr(upstream, "category_prices", lambda category: self._get("category_prices"))
        for name in ("reconciliation_summary", "reconciliation_exceptions", "paid_without_tickets", "resale_summary"):
            monkeypatch.setattr(upstream, name, lambda auth, _n=name: self._get(_n))
        monkeypatch.setattr(upstream, "sales_timeline", lambda auth, a, b: self._get("sales_timeline"))

    def _get(self, name):
        self.calls.append(name)
        value = self.values[name]
        if isinstance(value, Exception):
            raise value
        return value


class FakeModel:
    """ai_invoke stand-in: answers from a queue, records every call."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.calls = []

    def __call__(self, profile, messages, actor=None, tools=None, tier=None):
        self.calls.append({"profile": profile, "messages": list(messages), "actor": actor, "tools": tools})
        answer = self.answers.pop(0) if self.answers else ""
        if isinstance(answer, Exception):
            raise answer
        if isinstance(answer, SimpleNamespace):
            return answer
        return SimpleNamespace(content=answer, tool_calls=[])


def tool_call(name, args=None, call_id="c1"):
    return SimpleNamespace(content="", tool_calls=[{"name": name, "args": args or {}, "id": call_id}])
