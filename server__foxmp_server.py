#!/usr/bin/env python3
"""FoxMP server v4 (stdlib only, python 3.8+; runs in Termux / PC / VPS)
  TCP  7778  control, JSON lines: login, friends, requests, worlds, mission flag, chat
  UDP  7777  realtime: 'J' player state, 'V' voice, 'K' keepalive (relayed inside the same world)
  HTTP 7779  world save transfer: host POST /save/<name>, guests GET /save/<name>
Run: python3 foxmp_server.py     (data is kept in ./foxmp_data)
"""
import asyncio, json, os, hashlib, threading, struct
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TCP_PORT, UDP_PORT, HTTP_PORT = 7778, 7777, 7779
DATA = os.environ.get("FOXMP_DATA") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "foxmp_data")
os.makedirs(os.path.join(DATA, "saves"), exist_ok=True)
DB_FILE = os.path.join(DATA, "db.json")
db = {"users": {}}
if os.path.exists(DB_FILE):
    db = json.load(open(DB_FILE))


def save_db():
    json.dump(db, open(DB_FILE + ".tmp", "w"))
    os.replace(DB_FILE + ".tmp", DB_FILE)


class Player:
    def __init__(self, name, writer, pid):
        self.name, self.w, self.pid = name, writer, pid
        self.world = name      # everyone starts in their own world
        self.addr = None
        self.mission = False


online = {}   # name -> Player
by_pid = {}   # pid  -> Player
next_pid = [1]


def send(p, obj):
    try:
        p.w.write((json.dumps(obj) + "\n").encode())
    except Exception:
        pass


def users_in(world):
    return [p for p in online.values() if p.world == world]


def saved(world):
    return os.path.exists(os.path.join(DATA, "saves", world + ".zip"))


def friends_view(name):
    u = db["users"][name]
    return {"t": "friends",
            "friends": [{"name": f, "online": f in online, "world": online[f].world if f in online else ""} for f in u["friends"]],
            "pending": u["pending"]}


def push_friends(name):
    if name in online:
        send(online[name], friends_view(name))


def push_roster(world):
    r = {"t": "roster", "world": world, "save": saved(world),
         "players": [{"pid": p.pid, "name": p.name, "mission": p.mission} for p in users_in(world)]}
    for p in users_in(world):
        send(p, r)


async def handle(reader, writer):
    me = None
    try:
        while True:
            line = await asyncio.wait_for(reader.readline(), 90)
            if not line:
                break
            m = json.loads(line)
            t = m.get("t")
            if t == "login":
                name = "".join(c for c in str(m.get("name", "")) if c.isalnum() or c in "_-")[:16] or "player"
                if name in online:
                    writer.write(b'{"t":"err","msg":"name already online"}\n')
                    break
                db["users"].setdefault(name, {"friends": [], "pending": []})
                save_db()
                me = Player(name, writer, next_pid[0])
                next_pid[0] += 1
                online[name] = me
                by_pid[me.pid] = me
                send(me, {"t": "welcome", "pid": me.pid, "name": name, "udp": UDP_PORT, "http": HTTP_PORT})
                send(me, friends_view(name))
                push_roster(me.world)
                for f in db["users"][name]["friends"]:
                    push_friends(f)
            elif me is None:
                break
            elif t == "ping":
                send(me, {"t": "pong"})
            elif t == "friend.request":
                to = str(m.get("to", ""))
                u = db["users"].get(to)
                if u and to != me.name and me.name not in u["pending"] and to not in db["users"][me.name]["friends"]:
                    u["pending"].append(me.name)
                    save_db()
                    push_friends(to)
                    if to in online:
                        send(online[to], {"t": "notice", "msg": me.name + " tomake friend request pathiyeche"})
                    send(me, {"t": "notice", "msg": "request pathano hoyeche"})
                else:
                    send(me, {"t": "notice", "msg": "request jay ni (user nei ba already friend)"})
            elif t in ("friend.accept", "friend.reject"):
                fr = str(m.get("from", ""))
                pend = db["users"][me.name]["pending"]
                if fr in pend:
                    pend.remove(fr)
                    if t == "friend.accept":
                        for a, b in ((me.name, fr), (fr, me.name)):
                            if b not in db["users"][a]["friends"]:
                                db["users"][a]["friends"].append(b)
                    save_db()
                    push_friends(me.name)
                    push_friends(fr)
            elif t == "world.join":
                host = str(m.get("host", ""))
                ok = host == me.name or (host in online and host in db["users"][me.name]["friends"])
                if ok:
                    old = me.world
                    me.world, me.mission = host, False
                    push_roster(old)
                    push_roster(host)
                    send(me, {"t": "world.joined", "host": host, "save": saved(host)})
                else:
                    send(me, {"t": "notice", "msg": "world e dhukte parlam na (friend na ba offline)"})
            elif t == "world.leave":
                old = me.world
                me.world = me.name
                push_roster(old)
                push_roster(me.name)
                send(me, {"t": "world.joined", "host": me.name, "save": False})
            elif t == "invite":          # GTA-Online style: invite a friend into my current world
                to = str(m.get("to", ""))
                if to in online and to in db["users"][me.name]["friends"]:
                    send(online[to], {"t": "invite", "from": me.name, "world": me.world})
                    send(me, {"t": "notice", "msg": to + " ke invite pathano hoyeche"})
            elif t == "kick":            # only the world owner can kick
                who = online.get(str(m.get("name", "")))
                if who and who is not me and me.world == me.name and who.world == me.name:
                    who.world, who.mission = who.name, False
                    send(who, {"t": "kicked", "by": me.name})
                    send(who, {"t": "world.joined", "host": who.name, "save": False})
                    push_roster(me.name); push_roster(who.name)
            elif t == "time":            # world owner's clock is the shared clock
                if me.world == me.name:
                    for p in users_in(me.world):
                        if p is not me: send(p, {"t": "time", "h": int(m.get("h", 12)) % 24, "m": int(m.get("m", 0)) % 60})
            elif t == "mission":
                me.mission = bool(m.get("on"))
                push_roster(me.world)
            elif t == "chat":
                for p in users_in(me.world):
                    send(p, {"t": "chat", "from": me.name, "msg": str(m.get("msg", ""))[:200]})
    except Exception:
        pass
    finally:
        if me:
            online.pop(me.name, None)
            by_pid.pop(me.pid, None)
            push_roster(me.world)
            for f in db["users"].get(me.name, {}).get("friends", []):
                push_friends(f)
        try:
            writer.close()
        except Exception:
            pass


class Udp(asyncio.DatagramProtocol):
    def connection_made(self, tr):
        self.tr = tr

    def datagram_received(self, data, addr):
        if len(data) < 5:
            return
        kind = data[:1]
        p = by_pid.get(struct.unpack_from("<I", data, 1)[0])
        if not p:
            return
        p.addr = addr
        if kind == b"K":
            return
        if p.mission and kind == b"J":      # mission players are instanced: not shown to others
            return
        for q in users_in(p.world):
            if q is not p and q.addr:
                self.tr.sendto(data, q.addr)


class Http(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _file(self):
        w = "".join(c for c in self.path.split("/")[-1] if c.isalnum() or c in "_-")
        return w, os.path.join(DATA, "saves", w + ".zip")

    def do_GET(self):
        w, f = self._file()
        if not os.path.exists(f):
            self.send_response(404)
            self.end_headers()
            return
        blob = open(f, "rb").read()
        self.send_response(200)
        self.send_header("Content-Length", str(len(blob)))
        self.send_header("X-SHA256", hashlib.sha256(blob).hexdigest())
        self.end_headers()
        for i in range(0, len(blob), 65536):
            self.wfile.write(blob[i:i + 65536])

    def do_POST(self):
        w, f = self._file()
        n = int(self.headers.get("Content-Length", 0))
        if w not in online or n > 400 * 1024 * 1024:
            self.send_response(403)
            self.end_headers()
            return
        with open(f + ".part", "wb") as fh:
            left = n
            while left > 0:
                b = self.rfile.read(min(65536, left))
                if not b:
                    break
                fh.write(b)
                left -= len(b)
        os.replace(f + ".part", f)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")


async def main():
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(Udp, local_addr=("0.0.0.0", UDP_PORT))
    srv = await asyncio.start_server(handle, "0.0.0.0", TCP_PORT)
    threading.Thread(target=ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Http).serve_forever, daemon=True).start()
    print("FoxMP v4 up  tcp:%d udp:%d http:%d" % (TCP_PORT, UDP_PORT, HTTP_PORT))
    async with srv:
        await srv.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
