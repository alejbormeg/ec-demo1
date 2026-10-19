#!/usr/bin/env python3
"""The demo's hands on ec1, for demo-prep.sh, opera-outage.sh and the flows of docs/poc-acl/demo.md.

Talks to the services through `kubectl port-forward` and reads their databases through the engine's
PostgreSQL pod; Opera through opera.py (GET only); Salesforce through the MDM's client (GET only). No
credential is printed.

  ec1.py health                             # PASS/FAIL table; exit 1 if anything FAILs
  ec1.py contracts                          # only the task contracts check (check_contracts.py --cluster)
  ec1.py book [--arrival D] [--nights N] [--room C] [--rate C] [--board C] [--channel C]
              [--first F --last L --email E --phone P] [--tag T]
                                            # a booking in the CRS (booking API); prints its locator
  ec1.py modify LOCATOR [--arrival D] [--nights N] [--room C] [--rate C] [--board C]
  ec1.py cancel LOCATOR [--reason OTR]
  ec1.py show LOCATOR [--wait SECONDS]      # the CRS, the engine, causes, the inbox, Opera, the front office
  ec1.py rate-plan HOTEL CODE "NAME" FACTOR # opens a rate plan in a hotel of the CRS (idempotent)
  ec1.py ask-agent HOTEL [--wait SECONDS]   # the mapping agent proposes the hotel's pending codes
  ec1.py proposal HOTEL CODE [--approve]    # the agent's proposal for one code; --approve approves only it
  ec1.py seed returning-customer [--create] | arriving-today | arriving-opera-today | walk-in
  ec1.py seed known-customers [--count N]   # recognised at the check-in: a document, a Riu Class number, a stay history

Every date is ISO (YYYY-MM-DD). Hotel MRU01 unless --hotel says otherwise.
"""
import argparse
import datetime
import json
import os
import random
import socket
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
NS = "ec-demo1"
PORTS = {"booking": 8108, "mapping-service": 8122, "integrations-service": 8126, "front-office": 8128,
         "crs-integration-service": 8121, "communication-service": 8125, "orchestrator": 8105}
TAG = "demo-prep"


# ---------------------------------------------------------------------------------------------- plumbing

def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


_forwards = {}


def url(service):
    """A local URL for a service of ec1, through a port-forward kept for the life of this process."""
    if service in _forwards:
        return _forwards[service][0]
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    proc = subprocess.Popen(["kubectl", "-n", NS, "port-forward", f"svc/{service}", f"{port}:{PORTS[service]}"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = f"http://127.0.0.1:{port}"
    for _ in range(100):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
            break
        except OSError:
            time.sleep(0.1)
    _forwards[service] = (base, proc)
    return base


def close_forwards():
    for _, proc in _forwards.values():
        proc.terminate()


def http(method, service, path, body=None, ok=(200, 201, 204)):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url(service) + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw.strip()[:1] in ("{", "[") else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        if e.code in ok:
            return e.code, raw
        try:
            detail = json.loads(raw).get("detail") or raw
        except ValueError:
            detail = raw
        raise SystemExit(f"{method} {service}{path}: {e.code} {detail}")


def get(service, path):
    return http("GET", service, path)[1]


_pg = None


def psql(db, sql):
    """Rows (lists of strings) of a query in one of ec1's databases."""
    global _pg
    if _pg is None:
        pods = run(["kubectl", "-n", NS, "get", "pod", "-o", "name"]).stdout.split()
        _pg = next(p for p in pods if "eventconductor-postgres" in p)
    r = run(["kubectl", "-n", NS, "exec", "-i", _pg, "--", "sh", "-c",
             f'psql -U "$POSTGRES_USER" -v ON_ERROR_STOP=1 -At -F "\x1f" -d {db}'], input=sql)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return [line.split("\x1f") for line in r.stdout.splitlines() if line]


def q(value):
    return "'" + str(value).replace("'", "''") + "'"


def opera(*args):
    r = run([sys.executable, str(HERE / "opera.py"), *args])
    return r.returncode, (r.stdout.strip() or r.stderr.strip())


def today():
    return datetime.date.today()


# ---------------------------------------------------------------------------------------------- the CRS

def booking(locator):
    return get("booking", f"/bookings/{locator}")


def bookings(size=500):
    return get("booking", f"/bookings?size={size}")


def request_of(b):
    """A booking as the booking API takes it back on a PUT: its terms, without prices."""
    return {"channelCode": b["channelCode"], "partnerCode": b.get("partnerCode"),
            "externalReference": b.get("externalReference"), "arrival": b["arrival"], "departure": b["departure"],
            "holder": b["holder"], "comments": b.get("comments"),
            "rooms": [{"roomTypeCode": r["roomTypeCode"], "ratePlanCode": r["ratePlanCode"], "boardCode": r["boardCode"],
                       "adults": r["adults"], "childrenAges": r.get("childrenAges") or [],
                       "guests": [{k: v for k, v in g.items() if v is not None} for g in r.get("guests") or []]}
                      for r in b["rooms"]]}


def create_booking(hotel="MRU01", channel="WEB", room="STD-KING", rate="DIRECTA", board="DESAYUNO", arrival=None,
                   nights=3, adults=2, first=None, last=None, email=None, phone=None, nationality="ES", tag=None,
                   document=None):
    arrival = arrival or (today() + datetime.timedelta(days=45)).isoformat()
    departure = (datetime.date.fromisoformat(arrival) + datetime.timedelta(days=nights)).isoformat()
    first = first or random.choice(["Lucía", "Marta", "Jonas", "Emma", "Pierre", "Giulia", "Hugo", "Ingrid", "Nora"])
    last = last or random.choice(["Ferrer", "Lindqvist", "Moreau", "Bianchi", "Okafor", "Novak", "Duarte", "Keller"])
    suffix = "".join(random.choices(string.digits, k=4))
    email = email or f"{first}.{last}.{suffix}@example.com".lower().replace("í", "i").replace("é", "e")
    guest = {"firstName": first, "lastName": last, "type": "Adult"}
    if document:
        guest.update({"documentType": "PASSPORT", "documentNumber": document})
    guests = [guest] + [{"firstName": "Acompañante", "lastName": last, "type": "Adult"} for _ in range(adults - 1)]
    body = {"hotelCode": hotel, "booking": {
        "channelCode": channel, "arrival": arrival, "departure": departure,
        "holder": {"firstName": first, "lastName": last, "email": email, "phone": phone, "nationality": nationality},
        "rooms": [{"roomTypeCode": room, "ratePlanCode": rate, "boardCode": board, "adults": adults,
                   "childrenAges": [], "guests": guests}],
        "comments": tag}}
    return http("POST", "booking", "/bookings", body)[1]["id"]


# ---------------------------------------------------------------------------------------------- show

def processes(locator):
    return psql("workflow", f"""
        select p.workflow_definition_id, p.status, to_char(p.created, 'HH24:MI:SS'), p.business_key,
               coalesce((select s.step_id || ' ' || s.status || ' attempts=' || s.attempt_count
                           from step_execution_entity s
                          where s.process_id = p.id and s.status not in ('COMPLETED', 'CANCELLED', 'CREATED')
                          order by s.started_at desc nulls last limit 1), '')
          from process_entity p
         where p.business_key like {q('%/' + locator + ':%')}
         order by p.created""")


def causes(locator):
    return psql("mapping", f"""
        select c.cause_key, c.status, c.type, c.description, to_char(c.opened_at, 'HH24:MI:SS'),
               coalesce(to_char(c.resolved_at, 'HH24:MI:SS'), ''), coalesce(c.resolved_by, '')
          from cause c
         where c.cause_key like {q('%' + locator + '%')}
            or c.cause_key in (select wc.cause_key from waiter_cause wc join waiter w on w.process_key = wc.process_key
                                where w.subject = {q(locator)})
         order by c.opened_at""")


def inbox(subject):
    return psql("communication", f"""
        select type, to_char(created_at, 'HH24:MI:SS'), coalesce(to_char(resolved_at, 'HH24:MI:SS'), 'open'), title
          from inbox_item where subject like {q('%' + subject + '%')} or title like {q('%' + subject + '%')}
         order by created_at""")


def stay(locator):
    rows = psql("front_office", f"""
        select id, status, check_in, check_out, room_type, board, pax, total, coalesce(room_number, ''),
               coalesce(pms_reservation_id, ''), coalesce(pms_version, '')
          from stay where id = {q(locator)}""")
    return rows[0] if rows else None


def show(locator, hotel="MRU01", quiet=False):
    b = booking(locator)
    room = b["rooms"][0]
    pms = (b.get("pmsReference") or {}).get("reservationId")
    print(f"CRS        {locator} v{b['version']} {b['status']}  {b['arrival']}..{b['departure']}  "
          f"{room['roomTypeCode']} / {room['ratePlanCode']} / {room['boardCode']}  {b['totalAmount']} {b['currency']}  "
          f"Opera: {pms or '-'}")
    running = False
    for d, status, at, key, step in processes(locator):
        running |= status not in ("COMPLETED", "CANCELLED", "FAILED")
        print(f"Engine     {d:22} {status:10} {at}  {step}")
    keys = []
    for key, status, typ, desc, opened, resolved, by in causes(locator):
        keys.append(key)
        print(f"Cause      {status:8} {key}  (opened {opened}{', resolved ' + resolved + ' by ' + by if resolved else ''})")
    seen = set()
    for subject in [locator] + keys:
        for typ, at, state, title in inbox(subject):
            if (typ, at) not in seen:
                seen.add((typ, at))
                print(f"Inbox      {typ:18} {at} {state:8} {title[:110]}")
    code, out = opera("locator", hotel if hotel != "MRU01" else "XMAR", locator)
    if code == 1 and out.startswith("{"):
        print(f"Opera      none under this locator (context {json.loads(out)['context']})")
    elif code == 0:
        found = json.loads(out)
        for r in found["reservations"]:
            print(f"Opera      {r['reservationId']} {r['status']}  {r['arrival']}..{r['departure']}  "
                  f"{r['roomType']} / {r['ratePlan']}  CRS version {r['udfs'].get('UDFN01', '-')}"
                  f"  ({found['count']} under context {found['context']})")
    else:
        print(f"Opera      none under this locator ({out[:120]})")
    s = stay(locator)
    if s:
        sid, status, ci, co, rt, board, pax, total, room_no, pms_id, pms_version = s
        print(f"Front off. {status} {ci}..{co}  {rt} / {board}  pax {pax}  {total}  room {room_no or '-'}"
              + (f"  from Opera {pms_id} as modified {pms_version}" if pms_id else "  (not from Opera)"))
    else:
        print("Front off. no stay")
    return running


# ---------------------------------------------------------------------------------------------- health

def health():
    rows = []

    def check(name, fn):
        try:
            status, detail = fn()
        except SystemExit as e:
            status, detail = "FAIL", str(e)
        except Exception as e:  # noqa: BLE001 - a check that breaks is a FAIL, with why
            status, detail = "FAIL", f"{type(e).__name__}: {e}"
        rows.append((name, status, detail))

    def deployments():
        items = json.loads(run(["kubectl", "-n", NS, "get", "deploy", "-o", "json"]).stdout)["items"]
        bad = [f"{d['metadata']['name']} {d['status'].get('readyReplicas', 0)}/{d['spec']['replicas']}"
               for d in items if d["spec"].get("replicas", 1) and d["status"].get("readyReplicas", 0) < d["spec"]["replicas"]]
        return ("FAIL", "not ready: " + ", ".join(bad)) if bad else ("PASS", f"{len(items)} deployments ready")

    def engine():
        h = get("orchestrator", "/actuator/health")
        running = psql("workflow", "select count(*) from process_entity where status not in ('COMPLETED','CANCELLED','FAILED')")[0][0]
        return ("PASS" if h.get("status") == "UP" else "FAIL"), f"orchestrator {h.get('status')}; {running} process(es) not finished"

    def opera_token():
        code, out = opera("token")
        return ("PASS" if code == 0 else "FAIL"), out

    def opera_property():
        code, out = opera("property", "XMAR")
        return ("PASS" if code == 0 else "FAIL"), out

    sf = {}

    def salesforce_session():
        if sf:
            return sf
        env = dict(os.environ)
        for key in ("SF_DOMAIN", "SF_CLIENT_ID", "SF_CLIENT_SECRET"):
            if not env.get(key):
                raw = run(["kubectl", "-n", NS, "get", "secret", "ec-salesforce", "-o", f"jsonpath={{.data.{key}}}"]).stdout
                import base64
                env[key] = base64.b64decode(raw).decode()
        body = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": env["SF_CLIENT_ID"],
                                       "client_secret": env["SF_CLIENT_SECRET"]}).encode()
        tok = json.load(urllib.request.urlopen(f"https://{env['SF_DOMAIN']}/services/oauth2/token", body, timeout=30))
        sf.update(tok)
        return sf

    def salesforce():
        tok = salesforce_session()
        req = urllib.request.Request(tok["instance_url"] + "/services/data/v67.0/query?q="
                                     + urllib.parse.quote("SELECT COUNT() FROM Contact"),
                                     headers={"Authorization": "Bearer " + tok["access_token"]})
        total = json.load(urllib.request.urlopen(req, timeout=30))["totalSize"]
        return "PASS", f"token granted; {total} contact(s) in the org"

    def salesforce_allowance():
        # The org's daily API allowance: every call in a rolling 24 h counts (15,000 on this Base
        # Edition). The limits resource answers even when it is spent. Flows 2 and 3 need Salesforce;
        # docs/poc-acl/demo.md has what each flow spends and what to do when it is short.
        reserve = int(os.environ.get("SF_API_RESERVE", "1000"))
        tok = salesforce_session()
        req = urllib.request.Request(tok["instance_url"] + "/services/data/v67.0/limits",
                                     headers={"Authorization": "Bearer " + tok["access_token"]})
        daily = json.load(urllib.request.urlopen(req, timeout=30))["DailyApiRequests"]
        left, most = daily["Remaining"], daily["Max"]
        detail = f"{left} of {most} API calls left in the rolling 24 h (demo reserve {reserve})"
        if left <= 0:
            return "FAIL", detail + " — spent: Salesforce refuses every call until earlier ones roll out"
        return ("PASS" if left >= reserve else "WARN"), detail

    def mdm_salesforce():
        rows_ = psql("customer_mdm", "select coalesce(salesforce_state, '-'), count(*) from customer group by 1 order by 1")
        waiting = psql("customer_mdm", "select count(*) from customer where salesforce_refresh_pending")[0][0] \
            if psql("customer_mdm", "select count(*) from information_schema.columns where table_name = 'customer' "
                                    "and column_name = 'salesforce_refresh_pending'")[0][0] != "0" else "0"
        states = {r[0]: int(r[1]) for r in rows_}
        detail = ", ".join(f"{n} {st.lower()}" for st, n in states.items()) or "no customers"
        detail += f"; {waiting} contact change(s) to read"
        stuck = states.get("FAILED", 0) + int(waiting)
        return ("PASS" if not stuck and states.get("PENDING", 0) == 0 else "WARN"), detail

    def integration():
        try:
            i = get("integrations-service", "/integrations/hotels/MRU01")
        except SystemExit as e:
            if " 404 " in str(e):
                return "INFO", "no integration for MRU01 (after zero.sh: flow 1 starts here)"
            raise
        detail = f"{i['status']} with {i['pmsHotelCode']}" + (f", waiting for {i['waitingFor']}" if i.get("waitingFor") else "")
        return ("PASS" if i["status"] == "ACTIVE" else "INFO"), detail

    def front_office_integration():
        try:
            i = get("integrations-service", "/integrations/front-office/XMAR")
        except SystemExit as e:
            if " 404 " in str(e):
                return "INFO", "no pms-fo integration for XMAR (after zero.sh: flow 1 creates it)"
            raise
        detail = f"{i['status']} → front office {i.get('frontOfficeCode') or '?'}" \
                 + (f", waiting for {i['waitingFor']}" if i.get("waitingFor") else "") \
                 + (f"; polled {i['lastPollAt']}, cursor {i.get('pollCursor')}" if i.get("lastPollAt") else "")
        return ("PASS" if i["status"] == "ACTIVE" else "INFO"), detail

    def dictionary():
        entries = get("mapping-service", "/entries")
        by = {}
        for e in entries:
            if e.get("hotelCode") in (None, "MRU01"):
                by[e["status"]] = by.get(e["status"], 0) + 1
        pending = get("mapping-service", "/pending?hotelCode=MRU01")
        summary = ", ".join(f"{n} {s.lower()}" for s, n in sorted(by.items())) or "empty"
        return "INFO", f"MRU01 + chain: {summary}; {len(pending)} code(s) of MRU01 without equivalence"

    def open_causes():
        open_ = get("mapping-service", "/causes")
        if not open_:
            return "PASS", "none open"
        return "WARN", "; ".join(f"{c['key']} ({c['waiting']} waiting)" for c in open_[:5]) + (" …" if len(open_) > 5 else "")

    def outage():
        pol = run(["kubectl", "-n", NS, "get", "networkpolicy", "demo-opera-outage", "-o", "name"])
        alert = run(["kubectl", "-n", NS, "get", "deploy", "pms-integration-service", "-o",
                     "jsonpath={.spec.template.spec.containers[0].env[?(@.name=='RETRY_ALERT_AFTER')].value}"]).stdout
        if pol.returncode == 0:
            return "FAIL", f"the Opera outage is ON (opera-outage.sh off); alert after {alert}"
        return ("PASS" if alert == "10m" else "WARN"), f"no outage; PMS retry alert after {alert or '10m (default)'}"

    def context():
        ctx = run(["kubectl", "-n", NS, "get", "configmap", "ec-demo-run", "-o",
                   "jsonpath={.data.OPERA_EXTERNAL_SYSTEM}"]).stdout.strip() or "ECDEMO1 (no ConfigMap)"
        return "INFO", ctx

    def rooms():
        free = psql("front_office", "select count(*) from room where occupancy = 'FREE'")[0][0]
        stays = psql("front_office", "select count(*) from stay")[0][0]
        from_opera = psql("front_office", "select count(*) from stay where pms_reservation_id is not null")[0][0]
        catalogue = psql("front_office", "select count(*) from pms_catalogue")[0][0]
        return "INFO", f"{free} free room(s), {stays} stay(s) ({from_opera} from Opera), {catalogue} PMS catalogue entries"

    def task_contracts():
        # Every task a definition the engine imported references has a worker, on its topic, and every
        # task topic a live consumer group (check_contracts.py).
        import check_contracts
        contracts, definitions, source = check_contracts.from_cluster()
        served, gaps, _ = check_contracts.check(contracts, definitions, source, cluster=True)
        if gaps:
            return "FAIL", f"{len(gaps)} gap(s): " + "; ".join(gaps[:3]) + (" …" if len(gaps) > 3 else "")
        return "PASS", f"{len(served)} task steps, each served by a live worker on its topic"

    check("Deployments", deployments)
    check("Engine", engine)
    check("Task contracts", task_contracts)
    check("Opera token", opera_token)
    check("Opera XMAR (GET)", opera_property)
    check("Salesforce token", salesforce)
    check("Salesforce API allowance", salesforce_allowance)
    check("MDM → Salesforce", mdm_salesforce)
    check("Integration MRU01", integration)
    check("Integration XMAR → FO", front_office_integration)
    check("Dictionary", dictionary)
    check("Causes", open_causes)
    check("Opera outage", outage)
    check("Opera context", context)
    check("Front office", rooms)
    width = max(len(r[0]) for r in rows)
    print(f"{'CHECK'.ljust(width)}  RESULT  DETAIL")
    for name, status, detail in rows:
        print(f"{name.ljust(width)}  {status:6}  {detail}")
    return 1 if any(r[1] == "FAIL" for r in rows) else 0


# ---------------------------------------------------------------------------------------------- seeds

def tagged(tag, status="Confirmed"):
    return [b for b in bookings() if (b.get("comments") or "") == tag and (status is None or b["status"] == status)]


def seed_returning_customer(create):
    tag = f"{TAG}:returning-customer"
    done = tagged(tag, None)
    if done:
        print(f"Already made: {done[0]['id']} ({done[0]['status']}) for {done[0]['holder']['firstName']} "
              f"{done[0]['holder']['lastName']}, {done[0]['holder']['email']}")
        return 0
    candidates = [b for b in bookings() if b["hotelCode"] == "MRU01" and b["status"] == "Confirmed"
                  and b.get("pmsReference") and not (b.get("comments") or "").startswith(TAG)]
    if not candidates:
        print("No MRU01 booking in Opera yet: walk flow 1 first (its 10 demo bookings are the customers who come back).")
        return 1
    candidates.sort(key=lambda b: (b["holder"].get("phone") is None, b["created"]))
    old = candidates[0]
    h = old["holder"]
    user, _, domain = (h.get("email") or f"{h['firstName']}.{h['lastName']}@example.com").partition("@")
    email = f"{user}.nuevo@{domain or 'example.com'}".lower()
    arrival = (today() + datetime.timedelta(days=60)).isoformat()
    print(f"Returning customer: {h['firstName']} {h['lastName']} — already the holder of {old['id']}"
          f" (Opera {old['pmsReference']['reservationId']}), email {h.get('email')}, phone {h.get('phone') or '-'}")
    print("Type in Call center → Bookings → New (the wizard):")
    print(f"  hotel MRU01, channel CALLCENTER, arrival {arrival}, 2 nights, 2 adults, STD-KING / DIRECTA / DESAYUNO")
    print(f"  holder: {h['firstName']} {h['lastName']}, nationality {h.get('nationality') or 'ES'}, "
          f"phone {h.get('phone') or '(none: leave empty)'}, email {email}  ← same name and phone, ANOTHER email")
    if create:
        locator = create_booking(channel="CALLCENTER", arrival=arrival, nights=2, first=h["firstName"], last=h["lastName"],
                                 email=email, phone=h.get("phone"), nationality=h.get("nationality") or "ES", tag=tag)
        print(f"Created: {locator}")
    return 0


def seed_arriving_today():
    tag = f"{TAG}:arriving-today"
    ready = [b for b in tagged(tag) if b["arrival"] == today().isoformat()]
    if ready:
        locator = ready[0]["id"]
        print(f"Already there: {locator}, arriving today")
    else:
        locator = create_booking(channel="CALLCENTER", arrival=today().isoformat(), nights=2, tag=tag)
        print(f"Created: {locator}, arriving today ({today()}), 2 nights, 2 adults — waiting for it to reach Opera and the front office")
    for _ in range(60):
        b = booking(locator)
        if b.get("pmsReference") and stay(locator):
            break
        time.sleep(3)
    show(locator)
    print(f"No show (flow 4): front office → the stay {locator} → mark No show on each guest.")
    return 0


def opera_business_date(hotel="XMAR"):
    code, out = opera("business-date", hotel)
    if code != 0 or "business date " not in out:
        raise RuntimeError(f"Opera's business date not readable: {out}")
    return out.split("business date ")[1].split()[0]


def seed_arriving_opera_today():
    """A booking arriving on Opera's business date: the one the check-in and check-out can be made with.

    Opera checks in only what arrives on its business date (FOF00067 otherwise), and XMAR's UAT date does
    not move with the calendar: a booking arriving today by the calendar is refused at the check-in."""
    business = opera_business_date()
    tag = f"{TAG}:arriving-opera-today"
    # JS-SEA is SJMB in XMAR, which has inspected rooms: XMAR assigns no room that is only clean
    # (FOF00081), and its STDK rooms are all «Clean».
    # reused only while it is still to arrive: one the desk checked in or out is spent
    ready = [b for b in tagged(tag) if b["arrival"] == business and b["rooms"][0]["roomTypeCode"] == "JS-SEA"
             and (stay(b["id"]) is None or stay(b["id"])[1] == "ARRIVING")]
    if ready:
        locator = ready[0]["id"]
        print(f"Already there: {locator}, arriving on Opera's business date {business}")
    else:
        locator = create_booking(channel="CALLCENTER", arrival=business, nights=1, room="JS-SEA", tag=tag)
        print(f"Created: {locator}, arriving {business} (Opera's business date), 1 night, 2 adults, JS-SEA — "
              f"waiting for it to reach Opera and the front office")
    for _ in range(60):
        b = booking(locator)
        if b.get("pmsReference") and stay(locator):
            break
        time.sleep(3)
    show(locator)
    code, out = opera("rooms", "XMAR", "SJMB")
    free = [line.split("\t")[0] for line in out.splitlines() if "\tInspected\tVacant" in line]
    if not free:
        # every check-out the demo makes leaves its room Dirty, and the UAT has no housekeeping: put back
        # to Inspected the rooms our own check-outs dirtied (no one else's), and look again
        r = run([sys.executable, str(HERE / "opera-restore-our-rooms.py"), "XMAR", business, "--yes"])
        print((r.stdout or r.stderr).strip())
        code, out = opera("rooms", "XMAR", "SJMB")
        free = [line.split("\t")[0] for line in out.splitlines() if "\tInspected\tVacant" in line]
    print(f"SJMB rooms Opera has inspected and vacant: {', '.join(free[:8]) or 'none — pick another type'}")
    print(f"Check-in / check-out (front office → the stay {locator}): pick one of those rooms (a room only «Limpia, sin "
          f"inspeccionar en Opera» is refused); Opera → «en casa». The check-out is an early departure (Opera's date).")
    return 0


def seed_walk_in():
    free = psql("front_office", "select room_number, type from room where occupancy = 'FREE' order by room_number")
    print("Nothing to create: the walk-in starts at the front office (Reservas → ＋ Walk-in). Suggested data:")
    print(f"  arrival today ({today()}), 1 night, 2 adults")
    print("  room type STD-KING «Estándar con cama king», rate DIRECTA, board DESAYUNO (the CRS's codes for MRU01)")
    print(f"  holder: {random.choice(['Elena', 'Tomás', 'Sara', 'Jan', 'Aino'])} "
          f"{random.choice(['Paredes', 'Vidal', 'Kowalski', 'Lund', 'Serra'])}, nationality ES, "
          f"passport X{random.randint(1000000, 9999999)}")
    print(f"  free rooms now: {len(free)}" + (" — " + ", ".join(f"{n} ({t or '-'})" for n, t in free[:6]) if free else
                                             " — free one before the demo"))
    print("Needs WALKIN → WLK in the dictionary (flow 1 approves it).")
    return 0


def seed_known_customers(count, as_json=False):
    """A few guests of today's and the coming arrivals, recognised at the check-in as known customers.

    Their customer codes are random on every reset, so this runs after the onboarding: the front office
    picks holders with an MDM code (C-…) and, for each, gives the MDM their demo document (the one the
    scanner reads) and a Riu Class number, has customer-history seed past stays, and sets their tier and
    points. Run it again and it does the same to the same guests."""
    status, seeded = http("POST", "front-office", f"/demo/known-customers?count={count}")
    if as_json:
        print(json.dumps(seeded or []))
        return 0 if seeded else 1
    if not seeded:
        print("No arriving or in-house guest with an MDM code: walk the onboarding first.")
        return 1
    print(f"{len(seeded)} known customer(s) — scan their document, or search by the Riu Class number:")
    for k in seeded:
        print(f"  {k.get('guestName', '?'):28} {k.get('customerId', '?'):16} Riu Class {k.get('riuClass', '?'):12} "
              f"{k.get('documentType', '?')} {k.get('documentNumber', '?')}  (stay {k.get('stayId', '?')})")
    return 0


# ---------------------------------------------------------------------------------------------- main

def main(argv):
    ap = argparse.ArgumentParser(prog="ec1.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("health")
    sub.add_parser("contracts")
    for name in ("book", "modify"):
        p = sub.add_parser(name)
        if name == "modify":
            p.add_argument("locator")
        p.add_argument("--hotel", default="MRU01")
        p.add_argument("--arrival")
        p.add_argument("--nights", type=int)
        p.add_argument("--room")
        p.add_argument("--rate")
        p.add_argument("--board")
        p.add_argument("--channel")
        if name == "book":
            p.add_argument("--adults", type=int, default=2)
            p.add_argument("--first")
            p.add_argument("--last")
            p.add_argument("--email")
            p.add_argument("--phone")
            p.add_argument("--tag")
    p = sub.add_parser("cancel")
    p.add_argument("locator")
    p.add_argument("--reason", default="OTR")
    p = sub.add_parser("show")
    p.add_argument("locator")
    p.add_argument("--wait", type=int, default=0, help="seconds to wait for its processes to finish")
    p = sub.add_parser("rate-plan")
    p.add_argument("hotel")
    p.add_argument("code")
    p.add_argument("name")
    p.add_argument("factor")
    p = sub.add_parser("ask-agent")
    p.add_argument("hotel")
    p.add_argument("--wait", type=int, default=240)
    p = sub.add_parser("proposal")
    p.add_argument("hotel")
    p.add_argument("code")
    p.add_argument("--approve", action="store_true")
    p.add_argument("--by", default="demo")
    p = sub.add_parser("seed")
    p.add_argument("what", choices=["returning-customer", "arriving-today", "arriving-opera-today", "walk-in",
                                    "known-customers"])
    p.add_argument("--create", action="store_true")
    p.add_argument("--count", type=int, default=3)
    p.add_argument("--json", action="store_true", help="known-customers: print what was seeded as JSON")
    a = ap.parse_args(argv)

    if a.cmd == "health":
        return health()
    if a.cmd == "contracts":
        import check_contracts
        return check_contracts.main(["--cluster"])
    if a.cmd == "book":
        locator = create_booking(hotel=a.hotel, channel=a.channel or "WEB", room=a.room or "STD-KING",
                                 rate=a.rate or "DIRECTA", board=a.board or "DESAYUNO", arrival=a.arrival,
                                 nights=a.nights or 3, adults=a.adults, first=a.first, last=a.last, email=a.email,
                                 phone=a.phone, tag=a.tag)
        print(locator)
        return 0
    if a.cmd == "modify":
        b = booking(a.locator)
        req = request_of(b)
        arrival = a.arrival or b["arrival"]
        nights = a.nights or (datetime.date.fromisoformat(b["departure"]) - datetime.date.fromisoformat(b["arrival"])).days
        req["arrival"] = arrival
        req["departure"] = (datetime.date.fromisoformat(arrival) + datetime.timedelta(days=nights)).isoformat()
        for room in req["rooms"]:
            room["roomTypeCode"] = a.room or room["roomTypeCode"]
            room["ratePlanCode"] = a.rate or room["ratePlanCode"]
            room["boardCode"] = a.board or room["boardCode"]
        if a.channel:
            req["channelCode"] = a.channel
        http("PUT", "booking", f"/bookings/{a.locator}", req)
        after = booking(a.locator)
        print(f"{a.locator}: v{b['version']} → v{after['version']}  {after['arrival']}..{after['departure']}  "
              f"{after['rooms'][0]['roomTypeCode']}  {b['totalAmount']} → {after['totalAmount']} {after['currency']}")
        return 0
    if a.cmd == "cancel":
        http("POST", "booking", f"/bookings/{a.locator}/cancel", {"reasonCode": a.reason})
        after = booking(a.locator)
        print(f"{a.locator}: v{after['version']} {after['status']} ({a.reason})")
        return 0
    if a.cmd == "show":
        deadline = time.time() + a.wait
        while True:
            running = show(a.locator)
            if not running or time.time() >= deadline:
                return 0
            print(f"… still running; again in 10 s (until {datetime.datetime.fromtimestamp(deadline):%H:%M:%S})\n")
            time.sleep(10)
    if a.cmd == "rate-plan":
        status, result = http("POST", "booking", f"/catalog/hotels/{a.hotel}/rate-plans?by=demo-prep",
                              {"code": a.code, "name": a.name, "factor": float(a.factor)})
        plan = result["ratePlan"]
        print(f"{a.hotel}: rate plan {plan['code']} «{plan['name']}» factor {plan['factor']} — "
              f"{'opened' if status == 201 else 'already open, unchanged'}")
        return 0
    if a.cmd == "ask-agent":
        pending = [p for p in get("mapping-service", f"/pending?hotelCode={a.hotel}") if not p["proposed"]]
        if not pending:
            print(f"{a.hotel}: nothing pending without a proposal")
            return 0
        print(f"{a.hotel}: asking the agent for {len(pending)} code(s): " + ", ".join(f"{p['type']} {p['code']}" for p in pending))
        http("POST", "mapping-service", f"/agent-proposals?hotelCode={a.hotel}")
        started, deadline = time.time(), time.time() + a.wait
        while time.time() < deadline:
            left = [p for p in get("mapping-service", f"/pending?hotelCode={a.hotel}") if not p["proposed"]]
            if not left:
                print(f"  every code has a proposal ({time.time() - started:.0f} s)")
                return 0
            time.sleep(5)
        print(f"  still without a proposal after {a.wait} s: " + ", ".join(p["code"] for p in left))
        return 1
    if a.cmd == "proposal":
        entries = [e for e in get("mapping-service", "/entries")
                   if e["sourceCode"] == a.code and e.get("hotelCode") in (a.hotel, None)]
        if not entries:
            print(f"No entry for {a.code} of {a.hotel}")
            return 1
        for e in entries:
            print(f"{e['status']:10} {e['type']} {e['sourceCode']} → {e['targetCode']}  confidence {e.get('confidence')}"
                  f"  by {e.get('proposedBy')}  «{(e.get('rationale') or '')[:160]}»")
        if a.approve:
            proposed = [e for e in entries if e["status"] == "PROPOSED"]
            if not proposed:
                print("Nothing proposed to approve")
                return 1
            done = http("POST", "mapping-service", f"/entries/{proposed[0]['id']}/approve?by={urllib.parse.quote(a.by)}")[1]
            print(f"APPROVED   {done['type']} {done['sourceCode']} → {done['targetCode']} by {a.by}")
        return 0
    if a.cmd == "seed":
        if a.what == "returning-customer":
            return seed_returning_customer(a.create)
        if a.what == "arriving-today":
            return seed_arriving_today()
        if a.what == "arriving-opera-today":
            return seed_arriving_opera_today()
        if a.what == "known-customers":
            return seed_known_customers(a.count, a.json)
        return seed_walk_in()
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    finally:
        close_forwards()
