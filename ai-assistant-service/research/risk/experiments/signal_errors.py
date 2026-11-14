"""Dev-split error listing for one signal: python -m research.risk.experiments.signal_errors C1 DUPLICATE (dev only, never the test split)."""
import json, os, sys
from research.risk import common, run_configs as rc
from app.services.risk import content, rules

G = {r["id"]: r for r in common.read_jsonl(common.GOLD_PATH)}
DEV = set(json.load(open(common.SPLIT_PATH, encoding="utf-8"))["dev"])
_, CASES = common.load_cases()
BY = {c["id"]: c for c in CASES}


def dev_rows(config: str) -> dict:
    folder = os.path.join(common.RESULTS, config)
    for name in sorted(os.listdir(folder), reverse=True):
        rows = common.read_jsonl(os.path.join(folder, name))
        if rows and rows[0].get("split") == "dev" and not rows[0].get("limit") and rows[0].get("layer_a") == "full" and rows[0].get("llm") != "fake":
            return {r["id"]: r for r in rows[1:] if r["repeat"] == 0 and r["status"] == "ok"}
    raise SystemExit("no dev run")


if __name__ == "__main__":
    config, code = sys.argv[1], sys.argv[2]
    rows = dev_rows(config)
    pred = {i for i, r in rows.items() if code in r["codes"]}
    gold = {i for i in DEV if code in G[i]["codes"]}
    print(code, "predicted", len(pred), "gold", len(gold), "tp", len(pred & gold), "fp", len(pred - gold), "fn", len(gold - pred))
    for i in sorted(pred - gold)[:12]:
        c = BY[i]
        if code == "DUPLICATE":
            found = [r["finding"] for r in rc.signal_results(c) if r["name"] == "near_duplicate" and r.get("finding")]
            print("  FP", i, "|", c["event"]["title"], "|", found[0]["evidence"][0]["text"] if found else "?", "| gold", G[i]["level"], G[i]["codes"])
        else:
            texts = content.build(c["event"])["texts"]
            hits = [e["text"] for f in rules.evaluate(texts, rules.load_rules()) if f["code"].lower() == code.lower() for e in f["evidence"]]
            print("  FP", i, "|", hits, "| gold", G[i]["level"], G[i]["codes"])
    for i in sorted(gold - pred)[:8]:
        print("  FN", i, "|", BY[i]["event"]["title"], "|", BY[i]["event"]["description"].split(". ")[-1][:100], "| gold", G[i]["level"])
