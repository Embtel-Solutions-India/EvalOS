#!/usr/bin/env python3
"""Fill a blank GHL opportunity source from its contact's Source. Dry run by default.

GHL's API cannot set an opportunity's native Source (neither PUT nor upsert takes it), so the
value goes into the opportunity's "Lead Source" custom field (opportunity.lead_source), which
EvalOS reads as the fallback behind Source (board and GM overview).

  python scripts/source-backfill-dryrun.py                 # read-only report + CSV
  python scripts/source-backfill-dryrun.py --apply         # writes the FILL rows, after you type the count

Reads the live GHL account (not the EvalOS mirror). Safe to re-run, weekly or after an import: it only
touches opportunities where BOTH Source and Lead Source are blank, and never overwrites a value.
A FILL whose value is not one of the Lead Source field's options is reported as NOT_AN_OPTION and
skipped, because a single-select field may refuse or silently drop an unlisted value.

Env (or repo-root .env): GHL_API_TOKEN, GHL_LOCATION_ID.
Writes one CSV row per blank opportunity, and prints a summary:
  FILL          contact has a Source, the opportunity has none -> the proposed value
  NOT_AN_OPTION contact's Source is not an option of the Lead Source field -> skipped
  NO_SOURCE     neither has one -> needs a manual fill
"""
import argparse, csv, json, os, sys, time, urllib.parse, urllib.request
from collections import Counter
from pathlib import Path

BASE = "https://services.leadconnectorhq.com"
PAUSE = 0.12  # GHL allows 100 requests / 10 s; stay under it


def env(name):
    if os.environ.get(name):
        return os.environ[name]
    f = Path(__file__).resolve().parent.parent / ".env"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip().strip("\"'")
    sys.exit(f"{name} is not set")


TOKEN, LOCATION = env("GHL_API_TOKEN"), env("GHL_LOCATION_ID")


def get(path, **params):
    req = urllib.request.Request(
        f"{BASE}{path}?{urllib.parse.urlencode(params)}",
        headers={"Authorization": f"Bearer {TOKEN}", "Version": "2021-07-28", "Accept": "application/json", "User-Agent": "evalos-dryrun/1"})
    for attempt in range(4):
        try:
            time.sleep(PAUSE)
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 3:
                time.sleep(10)
                continue
            raise


def post_json(method, path, body):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(body).encode(), method=method,
        headers={"Authorization": f"Bearer {TOKEN}", "Version": "2021-07-28", "Accept": "application/json",
                 "Content-Type": "application/json", "User-Agent": "evalos-dryrun/1"})
    time.sleep(PAUSE)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status


def lead_source_field():
    for f in get(f"/locations/{LOCATION}/customFields", model="opportunity").get("customFields", []):
        if f.get("fieldKey") == "opportunity.lead_source":
            return f["id"], set(f.get("picklistOptions") or f.get("options") or [])
    sys.exit("opportunity.lead_source is not defined in this location")


def field_value(opp, field_id):
    for f in opp.get("customFields") or []:
        if f.get("id") == field_id:
            v = f.get("fieldValueString") or f.get("fieldValue")
            return None if blank(v) else v
    return None


def blank(v):
    return v is None or not str(v).strip()


def all_opportunities():
    after, after_id, rows = None, None, []
    while True:
        p = {"location_id": LOCATION, "limit": 100}
        if after and after_id:
            p.update(startAfter=after, startAfterId=after_id)
        page = get("/opportunities/search", **p)
        found = page.get("opportunities") or []
        rows += found
        meta = page.get("meta") or {}
        if len(found) < 100 or not meta.get("startAfter") or not meta.get("startAfterId"):
            return rows
        after, after_id = meta["startAfter"], meta["startAfterId"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="source-backfill-dryrun.csv")
    ap.add_argument("--apply", action="store_true", help="write the FILL rows (default: report only)")
    args = ap.parse_args()
    out = args.out
    field_id, options = lead_source_field()

    opps = all_opportunities()
    unsourced = [o for o in opps if blank(o.get("source")) and not field_value(o, field_id)]
    print(f"{len(opps)} opportunities in GHL, {len(unsourced)} with neither Source nor Lead Source")

    contact_source = {}
    for cid in sorted({o.get("contactId") or (o.get("contact") or {}).get("id") for o in unsourced} - {None}):
        try:
            c = get(f"/contacts/{cid}").get("contact") or {}
        except urllib.error.HTTPError as e:
            c = {"_error": e.code}
        contact_source[cid] = None if blank(c.get("source")) else c["source"].strip()

    tally, values = Counter(), Counter()
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["opportunity_id", "name", "status", "contact_id", "action", "proposed_source"])
        for o in unsourced:
            cid = o.get("contactId") or (o.get("contact") or {}).get("id")
            src = contact_source.get(cid)
            action = "NO_SOURCE" if not src else "FILL" if src in options else "NOT_AN_OPTION"
            tally[(o.get("status"), action)] += 1
            if src:
                values[src] += 1
            w.writerow([o["id"], o.get("name"), o.get("status"), cid, action, src or ""])

    fill = sum(v for (s, a), v in tally.items() if a == "FILL")
    print(f"\nWould fill {fill} of {len(unsourced)}; {len(unsourced) - fill} stay blank "
          f"(no source on the contact, or not an option of the Lead Source field).")
    print("\nBy status:")
    for (status, action), n in sorted(tally.items(), key=lambda kv: (str(kv[0][0]), kv[0][1])):
        print(f"  {str(status):10} {action:10} {n}")
    print("\nValues it would write (check the spellings; 'linkedin' / 'LinkedIn' are different sources):")
    for v, n in values.most_common():
        print(f"  {n:5}  {v}")
    print(f"\nPer-opportunity detail: {out}")

    if not args.apply:
        print("Dry run: nothing was written. Add --apply to write the FILL rows.")
        return
    todo = [(o["id"], contact_source[o.get("contactId") or (o.get("contact") or {}).get("id")])
            for o in unsourced
            if contact_source.get(o.get("contactId") or (o.get("contact") or {}).get("id")) in options]
    if input(f"\nWrite Lead Source on {len(todo)} opportunities in GHL? Type {len(todo)} to confirm: ").strip() != str(len(todo)):
        sys.exit("Not confirmed; nothing written.")
    done = failed = 0
    for oid, value in todo:
        try:  # the same fields-only body EvalOS's own setOpportunityFields sends: no pipeline, stage or status
            post_json("PUT", f"/opportunities/{oid}", {"customFields": [{"id": field_id, "fieldValue": value}]})
            done += 1
        except urllib.error.HTTPError as e:
            failed += 1
            print(f"  FAILED {oid} -> HTTP {e.code}")
    print(f"Written: {done}, failed: {failed}")


if __name__ == "__main__":
    main()
