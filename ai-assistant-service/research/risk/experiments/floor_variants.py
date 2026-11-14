import json, sys
from collections import Counter
from research.risk import common, run_configs as rc
from research.risk.stats import macro_f1
from app.services.risk import engine
G={r["id"]:r for r in common.read_jsonl(common.GOLD_PATH)}
dev=set(json.load(open(common.SPLIT_PATH,encoding="utf-8"))["dev"])
_,cases=common.load_cases(); cases=[c for c in cases if c["id"] in dev]
gw=rc.Gateway("live","groq-oss20b")
PROFILE={"organizer_account_new","organizer_account_young","organizer_ekyc_not_approved","organizer_history_rejected"}
ANOM={"price_outlier","near_duplicate","schedule_anomaly"}
orig=engine.policy_floor
def variant(kind):
    def f(findings):
        level,because=orig(findings)
        pos=[x for x in findings if x.get("weight",0)>0]
        content=[x for x in pos if x["layer"] in ("RULE","AI")]
        prof=[x for x in pos if x["code"] in PROFILE]; anom=[x for x in pos if x["code"] in ANOM]
        if kind=="V1" and content and prof: level="HIGH"
        if kind=="V2" and content and (prof or anom): level="HIGH"
        if kind=="V3" and any(x["code"]=="contact_social" for x in content) and prof: level="HIGH"
        return level,because
    return f
for kind in ("V0","V1","V2","V3"):
    engine.policy_floor=variant(kind)
    out=[]
    for cfg in ("C1","C4","C5"):
        rows=[rc.run_case(cfg,c,gw,0,"full") for c in cases]
        g=[G[r["id"]]["level"] for r in rows]; p=[r["level"] for r in rows]
        hm=sum(1 for a,b in zip(g,p) if a=="HIGH" and b!="HIGH")/sum(1 for a in g if a=="HIGH")
        fh=sum(1 for a,b in zip(g,p) if a!="HIGH" and b=="HIGH")/sum(1 for a in g if a!="HIGH")
        out.append(f"{cfg}: F1 {macro_f1(g,p,list(common.LEVELS)):.3f} acc {sum(a==b for a,b in zip(g,p))/len(g):.3f} missHIGH {hm:.2f} falseHIGH {fh:.2f}")
    print(kind," | ".join(out))
