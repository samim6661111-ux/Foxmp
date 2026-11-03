import asyncio, json, socket, struct, subprocess, sys, time, os, urllib.request
SRV = os.path.join(os.path.dirname(__file__), "..", "server", "foxmp_server.py")

async def client(name):
    r, w = await asyncio.open_connection("127.0.0.1", 7778)
    async def tx(o): w.write((json.dumps(o) + "\n").encode()); await w.drain()
    async def rx(want, ok=lambda m: True):
        for _ in range(30):
            m = json.loads(await asyncio.wait_for(r.readline(), 3))
            if m["t"] == want and ok(m): return m
        raise AssertionError("no " + want)
    await tx({"t": "login", "name": name})
    return tx, rx, (await rx("welcome"))["pid"]

async def main():
    ta, ra, pa = await client("alice"); tb, rb, pb = await client("bob")
    await ta({"t": "friend.request", "to": "bob"})
    f = await rb("friends", lambda m: "alice" in m["pending"])
    await tb({"t": "friend.accept", "from": "alice"})
    f = await ra("friends", lambda m: m["friends"]); assert f["friends"][0]["name"] == "bob", f
    await tb({"t": "world.join", "host": "alice"})
    j = await rb("world.joined"); assert j["host"] == "alice"
    ro = await ra("roster", lambda m: len(m["players"]) == 2)
    ua = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); ub = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ub.settimeout(2); ua.sendto(b"K" + struct.pack("<I", pa), ("127.0.0.1", 7777)); ub.sendto(b"K" + struct.pack("<I", pb), ("127.0.0.1", 7777))
    await asyncio.sleep(0.2)
    ua.sendto(b"J" + struct.pack("<I", pa) + b"hello", ("127.0.0.1", 7777))
    d, _ = ub.recvfrom(2048); assert d.endswith(b"hello"), d
    await ta({"t": "time", "h": 7, "m": 30}); tm = await rb("time"); assert (tm["h"], tm["m"]) == (7, 30), tm
    await ta({"t": "invite", "to": "bob"}); iv = await rb("invite"); assert iv["from"] == "alice"
    await ta({"t": "mission", "on": True}); await asyncio.sleep(0.2)
    ua.sendto(b"J" + struct.pack("<I", pa) + b"hidden", ("127.0.0.1", 7777))
    ub.settimeout(0.5)
    try: ub.recvfrom(2048); raise AssertionError("mission player leaked")
    except socket.timeout: pass
    req = urllib.request.Request("http://127.0.0.1:7779/save/alice", data=b"ZIPDATA" * 1000, method="POST")
    assert urllib.request.urlopen(req).read() == b"ok"
    got = urllib.request.urlopen("http://127.0.0.1:7779/save/alice"); assert len(got.read()) == 7000
    await tb({"t": "world.join", "host": "bob"}); await asyncio.sleep(0.2)
    await tb({"t": "kick", "name": "alice"}); await asyncio.sleep(0.2)   # bob is not owner of alice's world -> ignored
    await ta({"t": "world.join", "host": "alice"}); await tb({"t": "world.join", "host": "alice"}); await ra("world.joined")
    await ta({"t": "kick", "name": "bob"}); k = await rb("kicked"); assert k["by"] == "alice"
    print("ALL SERVER TESTS PASSED")

import tempfile
p = subprocess.Popen([sys.executable, SRV], env=dict(os.environ, FOXMP_DATA=tempfile.mkdtemp())); time.sleep(1.2)
try: asyncio.run(main())
finally: p.terminate()
