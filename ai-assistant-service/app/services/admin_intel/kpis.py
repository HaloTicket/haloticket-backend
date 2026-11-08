"""Dashboard KPIs and the "needs attention" feed. Deterministic: no model is called here.

A KPI whose source cannot be read is None and the source is named in `sources` as "unavailable"; it is never shown as 0.
"""
import hashlib
import json
import logging
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional
from urllib.parse import quote

from app.core.config import settings
from app.services.admin_intel import risk_service, upstream

log = logging.getLogger("ai.admin.kpis")

PENDING_STATES = ("PENDING", "UPDATE_PENDING")
LINKS = {"reconciliation": "/admin/reconciliation", "resale": "/admin/market/resale", "monitoring": "/admin/monitoring",
         "approval": "/admin/events-approval"}


def event_link(event_id: str) -> str:
    return f"{LINKS['approval']}/{quote(str(event_id), safe='')}"


def _parse(value) -> Optional[datetime]:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _hours_since(value, now: datetime) -> Optional[float]:
    moment = _parse(value)
    return None if moment is None else max(0.0, (now - moment).total_seconds() / 3600)


def _safe(sources: dict, name: str, function: Callable):
    try:
        value = function()
        sources[name] = "ok"
        return value
    except Exception as failure:
        log.warning("KPI source %s unavailable: %s %s", name, type(failure).__name__, str(failure)[:100])  # our own messages: path and status
        sources[name] = "unavailable"
        return None


def _health_summary(health: Optional[list]) -> Optional[dict]:
    if not health:
        return None
    up = [h for h in health if h.get("status") == "UP"]
    down = [h.get("name") for h in health if h.get("status") == "DOWN"]
    return {"up": len(up), "total": len(health), "down": down}


def kpi_hash(kpis: dict) -> str:
    body = {k: v for k, v in kpis.items() if k not in ("generated_at", "sources")}
    return hashlib.sha256(json.dumps(body, sort_keys=True, default=str, ensure_ascii=False).encode("utf-8")).hexdigest()[:16]


def build_kpis(authorization: str, actor: str, health: Optional[list], now: Optional[datetime] = None) -> dict:
    now = now or datetime.now(timezone.utc)
    sources: dict = {}
    events = _safe(sources, "events", lambda: upstream.approvals(authorization))
    pending = None
    live = None
    if events is not None:
        pending_events = [e for e in events if e.get("eventApprovalStatus") in PENDING_STATES]
        live = sum(1 for e in events if str(e.get("eventStatus")) == "LIVE")
        assessed = risk_service.ensure_assessments(authorization, actor, pending_events)
        by_risk = {"HIGH": 0, "MEDIUM": 0, "LOW": 0, "UNASSESSED": 0}
        for event in pending_events:
            found = assessed.get(str(event.get("id")))
            by_risk[found["risk_level"] if found else "UNASSESSED"] += 1
        pending = {"total": len(pending_events),
                   "pending": sum(1 for e in pending_events if e.get("eventApprovalStatus") == "PENDING"),
                   "update_pending": sum(1 for e in pending_events if e.get("eventApprovalStatus") == "UPDATE_PENDING"),
                   "by_risk": by_risk}

    resale = _safe(sources, "resale", lambda: upstream.resale_summary(authorization))
    if resale is not None:
        resale = {"count": int(resale.get("pendingCount", 0)), "oldest_hours": _hours_since(resale.get("oldestPendingAt"), now)}

    def timeline():
        start = (now - timedelta(days=6)).date().isoformat()
        data = upstream.sales_timeline(authorization, start, now.date().isoformat())
        days = [{"date": d.get("date"), "orders": int(d.get("orders") or 0), "revenue": float(d.get("revenue") or 0)}
                for d in (data.get("timeline") or [])]
        return {"days": days, "orders_total": sum(d["orders"] for d in days), "revenue_total": sum(d["revenue"] for d in days)}

    transactions = _safe(sources, "transactions", timeline)
    recon = _safe(sources, "reconciliation", lambda: upstream.reconciliation_summary(authorization))
    services = _health_summary(health)
    sources["services"] = "ok" if services else "unavailable"

    def count(name):
        if recon is None or recon.get(name) is None:
            return None
        return int(recon[name])

    kpis = {
        "generated_at": now.isoformat(),
        "pending_events": pending, "live_events": live, "resale_pending": resale, "transactions_7d": transactions,
        "late_payments_open": count("openExceptions"), "paid_without_tickets": count("paidWithoutTickets"),
        "stock_discrepancies": count("stockDiscrepancies"), "services": services, "sources": sources,
    }
    kpis["hash"] = kpi_hash(kpis)
    return kpis


def _item(kind: str, priority: str, title: str, evidence: dict, link: str, since: Optional[str] = None, ref: str = "") -> dict:
    return {"id": f"{kind}:{ref}" if ref else kind, "type": kind, "priority": priority, "title": title, "evidence": evidence,
            "link": link, "since": since}


PRIORITY_ORDER = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2}


def build_attention(authorization: str, actor: str, health: Optional[list], now: Optional[datetime] = None) -> dict:
    now = now or datetime.now(timezone.utc)
    sources: dict = {}
    items: list = []
    s = settings

    events = _safe(sources, "events", lambda: upstream.approvals(authorization))
    if events is not None:
        pending_events = [e for e in events if e.get("eventApprovalStatus") in PENDING_STATES]
        assessed = risk_service.ensure_assessments(authorization, actor, pending_events)
        for event in pending_events:
            found = assessed.get(str(event.get("id")))
            if not found or found["risk_level"] != "HIGH":
                continue
            age = _hours_since(event.get("submittedAt"), now)
            if age is not None and age < s.attn_high_risk_pending_hours:
                continue
            priority = "MEDIUM" if age is None else ("CRITICAL" if age >= s.attn_critical_pending_hours else "HIGH")
            reasons = [r["message"] for r in found["reason_items"] if r.get("status") != "not_assessed"][:3]
            items.append(_item("HIGH_RISK_PENDING", priority, f"Sự kiện rủi ro cao chờ duyệt: {event.get('title')}",
                               {"event_id": event.get("id"), "risk_score": found["risk_score"], "hours_waiting": None if age is None else round(age, 1),
                                "reasons": reasons}, event_link(event.get("id")), event.get("submittedAt"), str(event.get("id"))))

    exceptions = _safe(sources, "late_payments", lambda: upstream.reconciliation_exceptions(authorization))
    if exceptions is not None:
        for entry in exceptions.get("items") or []:
            if str(entry.get("status")).upper() != "OPEN":
                continue
            created = _parse(entry.get("createdAt"))
            minutes = None if created is None else (now - created).total_seconds() / 60
            if minutes is not None and minutes < s.attn_late_payment_minutes:
                continue
            items.append(_item("LATE_PAYMENT_OPEN", "HIGH", f"Thanh toán muộn chờ xử lý: {entry.get('bookingCode')}",
                               {"booking_code": entry.get("bookingCode"), "amount": entry.get("amount"), "kind": entry.get("kind"),
                                "minutes_open": None if minutes is None else int(minutes)}, LINKS["reconciliation"], entry.get("createdAt"), str(entry.get("id"))))

    paid = _safe(sources, "paid_without_tickets", lambda: upstream.paid_without_tickets(authorization))
    if paid is not None:
        for entry in paid.get("items") or []:
            minutes = entry.get("minutesSincePaid")
            if minutes is None or int(minutes) < s.attn_paid_without_tickets_minutes:
                continue
            priority = "CRITICAL" if int(minutes) >= 60 else "HIGH"
            items.append(_item("PAID_WITHOUT_TICKETS", priority, f"Đơn đã thanh toán nhưng thiếu vé: {entry.get('bookingCode')}",
                               {"booking_code": entry.get("bookingCode"), "expected_tickets": entry.get("expectedTickets"),
                                "issued_tickets": entry.get("issuedTickets"), "minutes_since_paid": int(minutes)},
                               LINKS["reconciliation"], entry.get("paidAt"), str(entry.get("bookingId"))))

    resale = _safe(sources, "resale", lambda: upstream.resale_summary(authorization))
    if resale is not None and int(resale.get("pendingCount", 0)) > 0:
        age = _hours_since(resale.get("oldestPendingAt"), now)
        if age is None or age >= s.attn_resale_pending_hours:
            items.append(_item("RESALE_PENDING", "MEDIUM", f"{int(resale['pendingCount'])} tin bán lại chờ duyệt",
                               {"count": int(resale["pendingCount"]), "oldest_hours": None if age is None else round(age, 1)},
                               LINKS["resale"], resale.get("oldestPendingAt")))

    sources["services"] = "ok" if health else "unavailable"
    for entry in health or []:
        if entry.get("status") == "DOWN":
            items.append(_item("SERVICE_DOWN", "CRITICAL", f"Dịch vụ không hoạt động: {entry.get('name')}",
                               {"name": entry.get("name"), "port": entry.get("port")}, LINKS["monitoring"], None, str(entry.get("name"))))

    items.sort(key=lambda i: (PRIORITY_ORDER[i["priority"]], i["since"] or "9999"))
    return {"generated_at": now.isoformat(), "items": items, "sources": sources}
