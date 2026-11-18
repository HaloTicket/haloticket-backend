"""Thử trên tập dev việc để một cờ của mô hình, đã được xác minh, độ tin cậy cao và thuộc loại tường minh, ép mức lên HIGH
(python -m research.risk.experiments.ai_high_floor [base|prompt_big]). Luật đang dùng: một cờ của mô hình đứng một mình thì tối đa là MEDIUM.
"""
import json, sys
from research.risk import common, run_configs as rc
from research.risk.experiments import model_variants as mv
from research.risk.stats import macro_f1
from app.services.risk import engine

variant = sys.argv[1] if len(sys.argv) > 1 else "prompt_big"
mv.install(variant)
gold = {r["id"]: r for r in common.read_jsonl(common.GOLD_PATH)}
dev = set(json.load(open(common.SPLIT_PATH, encoding="utf-8"))["dev"])
_, cases = common.load_cases(); cases = [c for c in cases if c["id"] in dev]
gateway = rc.Gateway("live", "groq-oss20b" if variant == "base" else f"variant-{variant}")
original = engine.ai_findings
for name, kinds in (("shipped", ()), ("HIGH for VEILED_PROHIBITED", ("VEILED_PROHIBITED",)), ("HIGH for VEILED_PROHIBITED+SCAM_PATTERN", ("VEILED_PROHIBITED", "SCAM_PATTERN"))):
    def patched(flags, kinds=kinds):
        """Bản thay cho engine.ai_findings: gọi bản gốc rồi đặt min_level là HIGH cho các phát hiện thuộc loại đang thử có độ tin cậy HIGH."""
        out = original(flags)
        for finding in out:
            if finding["code"] in kinds and finding.get("confidence") == "HIGH":
                finding["min_level"] = "HIGH"
        return out
    engine.ai_findings = patched
    rows = [rc.run_case("C5", c, gateway, 0, "full") for c in cases]
    g = [gold[r["id"]]["level"] for r in rows]; p = [r["level"] for r in rows]
    high = sum(1 for a in g if a == "HIGH")
    print(f"{variant} | {name}: F1={macro_f1(g, p, list(common.LEVELS)):.3f} acc={sum(a == b for a, b in zip(g, p)) / len(g):.3f} "
          f"missHIGH={sum(1 for a, b in zip(g, p) if a == 'HIGH' and b != 'HIGH')}/{high} "
          f"falseHIGH={sum(1 for a, b in zip(g, p) if a != 'HIGH' and b == 'HIGH')}/{len(g) - high}")
engine.ai_findings = original
