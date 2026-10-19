#!/usr/bin/env python3
"""Puts back to Inspected the rooms the demo's own check-outs left Dirty in Opera — and no others.

XMAR assigns only Inspected rooms (FOF00081) and its UAT has no housekeeping: every check-out the demo
makes (flows 9 and 10, the check-in / check-out seed) leaves its room Dirty, until none of a type is
left and the next check-in has no room. Ours are the rooms of our checked-out reservations — found by
the context of the CRS locator, as in opera-cancel-ours.py — arriving on the given date (the demo only
checks in on Opera's business date), still Dirty and Vacant. Rooms dirtied by anyone else stay as they are.

  opera-restore-our-rooms.py XMAR 2026-05-13          # lists them (nothing is written)
  opera-restore-our-rooms.py XMAR 2026-05-13 --yes    # puts them back to Inspected
"""
import json
import re
import sys
import urllib.request

sys.dont_write_bytecode = True
import opera  # noqa: E402  (same directory)

OURS = re.compile(r"^(ECDEMO1|ECLOCAL|EC1-)")


def our_rooms(hotel, arrival, access):
    """The rooms of our checked-out reservations arriving that day."""
    rooms, offset = set(), 0
    while True:
        page = opera.get(hotel, f"/rsv/v1/hotels/{hotel}/reservations?limit=200&offset={offset}"
                                f"&arrivalStartDate={arrival}&arrivalEndDate={arrival}", access)["reservations"]
        infos = page.get("reservationInfo") or []
        for info in infos:
            contexts = [e.get("idContext", "") for e in info.get("externalReferences") or []]
            room = (info.get("roomStay") or {}).get("roomId")
            if room and info.get("reservationStatus") == "CheckedOut" and any(OURS.match(c) for c in contexts):
                rooms.add(room)
        if not page.get("hasMore") or not infos:
            return rooms
        offset += len(infos)


def dirty_vacant(hotel, access):
    """Every room of the property that is Dirty and Vacant (the rooms search ignores a roomId filter)."""
    rooms, offset = set(), 0
    while True:
        found = opera.get(hotel, f"/fof/v1/hotels/{hotel}/rooms?limit=200&offset={offset}", access)
        details = found.get("hotelRoomsDetails") or {}
        page = details.get("room") or []
        for r in page:
            st = (r.get("housekeeping") or {}).get("roomStatus") or {}
            if st.get("roomStatus") == "Dirty" and st.get("frontOfficeStatus") == "Vacant":
                rooms.add(r.get("roomId"))
        if not details.get("hasMore") or not page:
            return rooms
        offset += len(page)


def inspect(hotel, rooms, access):
    body = json.dumps({"roomList": [{"roomId": r} for r in rooms], "housekeepingRoomStatus": "Inspected"}).encode()
    req = urllib.request.Request(f"{opera.GATEWAY}/hsk/v1/hotels/{hotel}/rooms/status", data=body, method="PUT",
                                 headers={"x-app-key": opera.APP_KEY, "x-hotelid": hotel,
                                          "Authorization": "Bearer " + access,
                                          "Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status


def main(argv):
    yes = "--yes" in argv
    hotel, arrival = [a for a in argv if a != "--yes"][:2]
    access = opera.token()["access_token"]
    todo = sorted(our_rooms(hotel, arrival, access) & dirty_vacant(hotel, access))
    print(f"{hotel}: rooms our check-outs left Dirty (arrival {arrival}): {', '.join(todo) or 'none'}")
    if todo and yes:
        inspect(hotel, todo, access)
        print(f"  back to Inspected: {', '.join(todo)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
