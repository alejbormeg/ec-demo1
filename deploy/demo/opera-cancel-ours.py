#!/usr/bin/env python3
"""Cancels in Opera every reservation the demo wrote — earlier runs' included — and nothing else.

Opera is the chain's UAT tenant, shared with RIU's own tests: zero.sh never cleans it, so each run
leaves its reservations there, holding rooms. This finds ours by the context of the CRS locator in
their external references — ECDEMO1 (the legacy one), ECDEMO1-<MMddHHmm> (ec1's runs, zero.sh),
ECLOCAL-<MMddHHmm> (deploy/local) and EC1-… (probes) — which only ReservationPayload writes.

  opera-cancel-ours.py [XMAR]          # lists them (nothing is written)
  opera-cancel-ours.py [XMAR] --yes    # cancels the Reserved / Requested ones (reason OTROS)

In-house reservations cannot be cancelled: they are listed, to be checked out by hand. The connection
is the cluster's ec-opera Secret, or OPERA_* in the environment (deploy/.secrets/credentials.env).
"""
import datetime
import json
import re
import sys
import urllib.error
import urllib.request

sys.dont_write_bytecode = True
import opera  # noqa: E402  (same directory)

OURS = re.compile(r"^(ECDEMO1|ECLOCAL|EC1-)")
CANCELLABLE = {"Reserved", "Requested"}
FROM, TO = datetime.date(2025, 1, 1), datetime.date(2029, 1, 1)


def months():
    d = FROM
    while d < TO:
        nxt = (d.replace(day=28) + datetime.timedelta(days=4)).replace(day=1)
        yield d, nxt - datetime.timedelta(days=1)
        d = nxt


def ours(hotel, access):
    """Every reservation of the property with one of our contexts, by arrival month (a search answers
    at most 1000 rows, however it is paged)."""
    found = {}
    for start, end in months():
        offset = 0
        while True:
            page = opera.get(hotel, f"/rsv/v1/hotels/{hotel}/reservations?limit=200&offset={offset}"
                                    f"&arrivalStartDate={start}&arrivalEndDate={end}", access)["reservations"]
            infos = page.get("reservationInfo") or []
            for info in infos:
                contexts = [e.get("idContext", "") for e in info.get("externalReferences") or []]
                mine = [c for c in contexts if OURS.match(c)]
                if mine:
                    rid = next(i["id"] for i in info["reservationIdList"] if i.get("type") == "Reservation")
                    locator = next((e.get("id") for e in info["externalReferences"] if e.get("idContext") == mine[0]), None)
                    found[rid] = {"id": rid, "context": mine[0], "locator": locator,
                                  "status": info.get("reservationStatus"),
                                  "arrival": (info.get("roomStay") or {}).get("arrivalDate")}
            if not page.get("hasMore") or not infos:
                break
            offset += len(infos)
    return sorted(found.values(), key=lambda r: (r["arrival"] or "", r["id"]))


def cancel(hotel, rid, access):
    body = json.dumps({"reason": {"code": "OTROS", "description": "ec-demo1: cleaning an earlier run's reservation"},
                       "reservations": [{"hotelId": hotel,
                                         "reservationIdList": [{"id": rid, "type": "Reservation"}]}]}).encode()
    req = urllib.request.Request(f"{opera.GATEWAY}/rsv/v1/hotels/{hotel}/reservations/{rid}/cancellations", data=body,
                                 method="POST", headers={"x-app-key": opera.APP_KEY, "x-hotelid": hotel,
                                                         "Authorization": "Bearer " + access,
                                                         "Content-Type": "application/json",
                                                         "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status


def main(argv):
    yes = "--yes" in argv
    args = [a for a in argv if a != "--yes"]
    hotel = args[0] if args else "XMAR"
    access = opera.token()["access_token"]
    found = ours(hotel, access)
    by = {}
    for r in found:
        by.setdefault(r["status"], []).append(r)
    print(f"{hotel}: {len(found)} reservations of ours — " +
          ", ".join(f"{s} {len(v)}" for s, v in sorted(by.items())))
    for r in by.get("InHouse", []):
        print(f"  in house, not cancellable: {r['id']} {r['context']}/{r['locator']} arrival {r['arrival']}")
    todo = [r for r in found if r["status"] in CANCELLABLE]
    if not yes:
        print(f"{len(todo)} to cancel: run again with --yes")
        return 0
    done, failed = 0, []
    for r in todo:
        try:
            cancel(hotel, r["id"], access)
            done += 1
        except urllib.error.HTTPError as e:
            if e.code == 401:  # the token's hour is up
                access = opera.token()["access_token"]
                try:
                    cancel(hotel, r["id"], access)
                    done += 1
                    continue
                except urllib.error.HTTPError as again:
                    e = again
            failed.append((r["id"], e.code, e.read()[:200].decode(errors="replace")))
        if (done + len(failed)) % 25 == 0:
            print(f"  {done + len(failed)}/{len(todo)}")
    print(f"cancelled {done} of {len(todo)}")
    for f in failed:
        print(f"  failed {f[0]}: {f[1]} {f[2]}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
