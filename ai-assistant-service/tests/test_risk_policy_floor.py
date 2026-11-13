"""Ruleset 5: a clear violation carries a minimum level taken from the labelling guide (policy floor); weak profile signals force
MEDIUM only together; the model alone never forces HIGH; a duplicate needs the title as well as the text."""
from datetime import datetime, timezone

import pytest

from app.core.config import settings
from app.services.risk import content, engine, rules, signals

NOW = datetime(2026, 11, 8, 12, 0, tzinfo=timezone.utc)


def rule(code, weight, min_level, severity="HIGH"):
    return {"layer": "RULE", "code": code, "severity": severity, "weight": weight, "message": "m", "evidence": [], "min_level": min_level}


def signal(code, weight, min_level="LOW"):
    return {"name": code, "status": "assessed", "finding": {"layer": "SIGNAL", "code": code, "severity": "MEDIUM", "weight": weight,
                                                          "message": "m", "evidence": [], "min_level": min_level}}


def level(rule_findings=None, signal_results=None, flags=None):
    return engine.combine(rule_findings or [], signal_results or [], "ran" if flags else "skipped", flags or [], "5")


def test_every_rule_in_the_file_has_a_valid_minimum_level():
    ruleset = rules.load_rules()
    assert ruleset.version == "5"
    assert {r.code: r.min_level for r in ruleset.rules} == {
        "contact_social": "MEDIUM", "prohibited_goods": "HIGH", "prohibited_ads": "HIGH", "alcohol_strength": "HIGH",
        "profit_guarantee": "HIGH", "overpromise": "MEDIUM"}
    found = rules.evaluate(content.build({"title": "Show", "description": "Tại sự kiện có bán thuốc lá giá rẻ."})["texts"], ruleset)
    assert found[0]["code"] == "prohibited_ads" and found[0]["min_level"] == "HIGH"


def test_one_clear_violation_reaches_its_policy_level_alone():
    assert level([rule("prohibited_ads", 40, "HIGH")])["risk_level"] == "HIGH"
    contact = level([rule("contact_social", 25, "MEDIUM")])
    assert contact["risk_level"] == "MEDIUM" and contact["risk_score"] == settings.risk_medium_threshold
    assert contact["coverage"]["policy_floor"] == {"level": "MEDIUM", "because": ["contact_social"]}
    assert level([rule("overpromise", 10, "MEDIUM", "MEDIUM")])["risk_level"] == "MEDIUM"


def test_an_informational_finding_forces_nothing():
    info = {"layer": "RULE", "code": "mentioned_in_prohibition", "severity": "INFO", "weight": 0, "message": "m", "evidence": [],
            "min_level": "HIGH"}
    assert level([info])["risk_level"] == "LOW" and "policy_floor" not in level([info])["coverage"]


def test_weak_profile_signals_force_medium_only_together():
    assert level(signal_results=[signal("organizer_ekyc_not_approved", 15)])["risk_level"] == "LOW"
    assert level(signal_results=[signal("organizer_account_young", 6)])["risk_level"] == "LOW"
    both = level(signal_results=[signal("organizer_account_young", 6), signal("organizer_ekyc_not_approved", 15)])
    assert both["risk_level"] == "MEDIUM" and both["coverage"]["policy_floor"]["because"] == ["organizer_account+ekyc"]
    assert level(signal_results=[signal("organizer_account_new", 12, "MEDIUM")])["risk_level"] == "MEDIUM"
    assert level(signal_results=[signal("organizer_history_rejected", 15, "MEDIUM")])["risk_level"] == "MEDIUM"


def test_the_model_alone_is_never_forced_above_medium():
    flags = [{"code": c, "confidence": "HIGH", "evidence": {"field": "description", "start": 0, "end": 3, "text": "abc"}}
             for c in ("VEILED_PROHIBITED", "SCAM_PATTERN", "OVERPROMISE", "SOCIAL_CONTACT")]
    result = level(flags=flags)
    assert result["risk_level"] == "MEDIUM" and result["risk_score"] <= settings.risk_ai_max_points
    one = level(flags=flags[:1])
    assert one["risk_level"] == "MEDIUM" and one["risk_score"] == settings.risk_medium_threshold


def test_a_content_violation_with_a_profile_signal_is_high_but_each_alone_is_not():
    contact = rule("contact_social", 25, "MEDIUM")
    assert level([contact])["risk_level"] == "MEDIUM"
    assert level(signal_results=[signal("organizer_history_rejected", 15, "MEDIUM")])["risk_level"] == "MEDIUM"
    both = level([contact], [signal("organizer_ekyc_not_approved", 15)])
    assert both["risk_level"] == "HIGH" and "content+organizer" in both["coverage"]["policy_floor"]["because"]
    # a price or schedule anomaly is not a profile signal: it stays MEDIUM next to a content violation
    assert level([contact], [signal("price_outlier", 10, "MEDIUM")])["risk_level"] == "MEDIUM"
    flag = [{"code": "SOCIAL_CONTACT", "confidence": "HIGH", "evidence": {"field": "description", "start": 0, "end": 3, "text": "abc"}}]
    assert level(flags=flag, signal_results=[signal("organizer_account_new", 12, "MEDIUM")])["risk_level"] == "HIGH"


def test_the_floor_never_lowers_a_higher_score():
    result = level([rule("prohibited_goods", 60, "HIGH", "BLOCK")], [signal("price_outlier", 10, "MEDIUM")])
    assert result["risk_level"] == "HIGH" and result["risk_score"] >= settings.risk_block_floor


# ---- duplicates need the title
ORIGINAL = {"id": "o1", "title": "Giải cờ vua Cần Thơ mở rộng", "description": "Giải có 15 nhóm tuổi, trao giải cho ba vị trí đầu mỗi nhóm. "
            "Vé bao gồm áo thi đấu, số báo danh và huy chương hoàn thành. Trạm nước và y tế đặt dọc đường chạy. "
            "Vận động viên nhận gói thi đấu từ ngày hôm trước.", "organizer": {"userId": "other"}, "eventApprovalStatus": "APPROVED"}


def duplicate(title, description):
    event = {"id": "e1", "title": title, "description": description, "organizer": {"userId": "me"}, "eventApprovalStatus": "PENDING",
             "categoryName": "Thể thao", "minPrice": 100000, "startTime": "2027-01-07T14:00:00+00:00", "endTime": "2027-01-07T17:00:00+00:00",
             "locationName": "Sân"}
    source = signals.SignalSource(user=lambda _u: {"createdAt": "2020-01-01T00:00:00Z", "ekycStatus": "APPROVED"},
                                  approvals=lambda: [ORIGINAL], category_prices=lambda _c: [100000] * 6)
    texts = content.build(event)["texts"]
    found = {r["name"]: r for r in signals.evaluate(event, texts, source, NOW)}["near_duplicate"]
    return found.get("finding")


def test_the_same_text_under_the_same_title_with_a_new_city_is_a_duplicate():
    finding = duplicate("Giải cờ vua Đà Nẵng mở rộng", ORIGINAL["description"])
    assert finding and finding["code"] == "near_duplicate" and finding["min_level"] == "MEDIUM"


def test_similar_text_under_an_unrelated_title_is_not():
    sentences = ORIGINAL["description"].split(". ")
    reworked = ". ".join(sentences[:3]) + ". Vận động viên nhận gói từ hôm trước. Cổng mở lúc năm giờ."
    # 0.74 on the description and 0.61 overall: version 4 flagged this, the title test now keeps it out
    assert signals.similarity("Đêm nhạc acoustic mùa thu " + reworked, ORIGINAL["title"] + " " + ORIGINAL["description"]) >= signals.DUPLICATE_THRESHOLD
    assert duplicate("Đêm nhạc acoustic mùa thu", reworked) is None
    assert duplicate("Giải cờ vua Đà Nẵng mở rộng", reworked) is not None


def test_an_almost_identical_text_is_a_duplicate_whatever_the_title():
    assert duplicate("Đêm nhạc acoustic mùa thu", ORIGINAL["description"]) is not None


def test_title_similarity():
    assert signals.title_similarity("Giải cờ vua Đà Nẵng mở rộng", "Giải cờ vua Cần Thơ mở rộng") == pytest.approx(5 / 9)
    assert signals.title_similarity("", "") == 0.0


def test_a_guarantee_of_profit_is_one_scam_not_also_an_exaggeration():
    profit = {"layer": "RULE", "code": "profit_guarantee", "severity": "BLOCK", "weight": 60, "message": "m", "min_level": "HIGH",
              "evidence": [{"field": "description", "start": 10, "end": 40, "text": "x"}]}
    overpromise = {"code": "OVERPROMISE", "confidence": "HIGH", "evidence": {"field": "description", "start": 12, "end": 30, "text": "y"}}
    elsewhere = {"code": "OVERPROMISE", "confidence": "HIGH", "evidence": {"field": "description", "start": 100, "end": 120, "text": "z"}}
    kept, dropped = engine.drop_flags_already_scored([overpromise, elsewhere], [profit])
    assert dropped == 1 and kept == [elsewhere]


def flag(code, confidence="HIGH", verified=False):
    return {"code": code, "confidence": confidence, "verified": verified,
            "evidence": {"field": "description", "start": 0, "end": 3, "text": "abc"}}


def test_only_a_verified_high_confidence_veiled_prohibited_flag_forces_high():
    assert level(flags=[flag("VEILED_PROHIBITED", verified=True)])["risk_level"] == "HIGH"
    assert level(flags=[flag("VEILED_PROHIBITED", verified=False)])["risk_level"] == "MEDIUM"        # no second reading: stays MEDIUM
    assert level(flags=[flag("VEILED_PROHIBITED", "MEDIUM", verified=True)])["risk_level"] == "MEDIUM"  # not explicit
    for code in ("SCAM_PATTERN", "SOCIAL_CONTACT", "OVERPROMISE"):
        assert level(flags=[flag(code, verified=True)])["risk_level"] == "MEDIUM"


def test_the_second_reading_marks_the_flags_it_confirmed(monkeypatch):
    from app.services.admin_intel import risk_service
    texts = {"title": "t", "description": "Có hàng trắng loại một cho khách"}
    built = {"texts": texts, "organizer_name": "o"}
    answers = iter(['{"flags": [{"code": "VEILED_PROHIBITED", "quote": "hàng trắng loại một", "confidence": "HIGH"}]}', '{"confirmed": ["VEILED_PROHIBITED"]}'])
    reply = lambda text: type("R", (), {"content": text, "response_metadata": {}})()
    state, flags, meta = risk_service._run_ai(built, "a", lambda profile, messages, actor=None: reply(next(answers)), None)
    assert state == "ran" and meta["verify"] == "ran" and flags and flags[0]["verified"] is True
    # when the second reading fails the flags are kept but unmarked, so they cannot force HIGH
    answers = iter(['{"flags": [{"code": "VEILED_PROHIBITED", "quote": "hàng trắng loại một", "confidence": "HIGH"}]}', "not json"])
    state, flags, meta = risk_service._run_ai(built, "a", lambda profile, messages, actor=None: reply(next(answers)), None)
    assert meta["verify"] == "failed" and flags and not flags[0].get("verified")
