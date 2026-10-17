// Cloud plans and the live service for tools/dev/mock-library.mjs (docs/design/cloud-live.md), in memory: the account's
// plans and the ones shared with it (REST), who may edit them, finding accounts, and the live service on the same port
// (GET /live/health, GET /live/nearby, and the WebSocket at /live: hello, join, ops applied in order, presence relayed,
// who is present, members pushed, and {t:"shared"} / {t:"unshared"} to every open socket of an account added to or
// taken off a plan). A WebSocket by hand, so it runs on plain node with nothing installed.
//
// For the notification: GET /mock/share?owner=dee&to=<account name>&name=<plan name> makes a plan owned by the stand-in
// (dee or eve) and shares it with that account, as if they had pressed Share; GET /mock/unshare?key=<doc key>&to=<name>
// takes it back.
//
// Two stand-in players, dee (in the game, as Dee_Mines) and eve (in a browser), have accounts here. Share a plan with
// one and they open it at once: present in its room, a cursor wandering over its cards and a card or two selected,
// ten times a second, as a real person would. /live/nearby answers dee whatever names the game asked with (they are
// logged), as if dee played on the same server.
//
// To use it: run the mock, then in the game
//   tools/dev/mc.sh call 'library?url=http://127.0.0.1:8789&live=ws://127.0.0.1:8789/live'
// and sign in (or make an account) from the Library. The account's site is then known to have cloud plans.
import { createHash, randomUUID } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

/** The website's twelve colours and its colorFor (FNV-1a over the id). */
const COLORS = ["#e5484d", "#f76b15", "#ffc53d", "#46a758", "#12a594", "#00a2c7", "#0090ff", "#3e63dd", "#8e4ec6",
  "#d6409f", "#a18072", "#868e2b"];
const colorFor = (id) => {
  let h = 0x811c9dc5;
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return COLORS[h % COLORS.length];
};

/**
 * Wires cloud plans and the live service into the mock's server. {@code accounts} is the mock's: id and name of the
 * account a request is signed in as ({@code who(req)}), and every account by name ({@code all()}).
 */
export function mockLive(server, { who, ids, users, stateFile }) {
  const BOTS = [
    { name: "dee", mcName: "Dee_Mines", client: "game" },
    { name: "eve", mcName: "", client: "web" },
  ];
  for (const b of BOTS) {
    if (!ids.has(b.name)) ids.set(b.name, randomUUID());
    if (!users.has(b.name)) users.set(b.name, "botpass1");
  }
  const nameOf = (id) => [...ids.entries()].find(([, v]) => v === id)?.[0] ?? "someone";
  const bot = (id) => BOTS.find((b) => ids.get(b.name) === id);

  /** Designs by `${owner}:${id}`: { ownerId, id, row (the list's fields), plan, seq, members: [ids], deletedAt }. */
  const designs = new Map();
  // Kept in stateFile as they change, so they outlast a restart of the mock as the accounts do.
  if (stateFile && existsSync(stateFile)) for (const d of JSON.parse(readFileSync(stateFile, "utf8"))) designs.set(`${d.ownerId}:${d.id}`, d);
  const save = () => stateFile && writeFileSync(stateFile, JSON.stringify([...designs.values()]));
  const member = (d) => d.members.map((id) => ({ id, username: nameOf(id) }));
  const listRow = (d) => ({ ...d.row, id: d.id, ownerId: d.ownerId, members: member(d), deletedAt: d.deletedAt ?? null });

  // region REST

  /** Answers the cloud routes; false when the request is not one of them. */
  async function rest(req, res, url, json) {
    const path = url.pathname;
    if (req.method === "GET" && path === "/live/health") return json(res, 200, { ok: true, rooms: rooms.size }), true;
    // For a look from a shell: every open room, who is in it, and the last presence each sent.
    if (req.method === "GET" && path === "/mock/rooms") {
      const out = {};
      for (const [key, room] of rooms) out[key] = {
        seq: room.seq,
        cards: room.plan.nodes?.length ?? 0,
        members: designs.get(key) ? member(designs.get(key)).map((m) => m.username) : [],
        present: [...room.conns].map((c) => ({ ...person(c), frames: c.heard ?? 0, last: c.last })),
      };
      return json(res, 200, out), true;
    }
    const me = who(req);
    if (req.method === "GET" && path === "/mock/share") {
      const owner = ids.get(url.searchParams.get("owner") ?? "dee"), to = ids.get(url.searchParams.get("to") ?? "");
      if (!owner || !to) return json(res, 404, { error: "No such account." }), true;
      const id = randomUUID(), key = `${owner}:${id}`;
      const stamp = new Date().toISOString();
      const row = {
        name: url.searchParams.get("name") ?? "A shared plan", closed: false, favorite: false, createdAt: stamp,
        updatedAt: stamp, planUpdatedAt: stamp,
      };
      designs.set(key, { ownerId: owner, id, row, plan: {}, seq: 0, members: [to] });
      save();
      toAccount(to, { t: "shared", doc: key, owner: { id: owner, username: nameOf(owner) }, name: row.name });
      return json(res, 200, { key }), true;
    }
    if (req.method === "GET" && path === "/mock/unshare") {
      const key = url.searchParams.get("key") ?? "", d = designs.get(key), to = ids.get(url.searchParams.get("to") ?? "");
      if (!d || !to) return json(res, 404, { error: "No such plan or account." }), true;
      d.members = d.members.filter((m) => m !== to);
      save();
      membersChanged(key, to);
      toAccount(to, { t: "unshared", doc: key });
      return json(res, 200, { ok: true }), true;
    }
    if (path === "/live/nearby" && req.method === "GET") {
      if (!me) return json(res, 401, { error: "Sign in first." }), true;
      const names = (url.searchParams.get("names") ?? "").split(",").filter(Boolean);
      console.log(`  nearby asked for ${names.length} names: ${names.slice(0, 8).join(", ")}`);
      // As the service: no names, nobody (and dee answers whatever names came, as if on the same server).
      const found = BOTS.filter((b) => names.length && b.mcName && ids.get(b.name) !== me.id).map((b) => ({
        id: ids.get(b.name), username: b.name, mcName: b.mcName,
      }));
      return json(res, 200, { accounts: found }), true;
    }
    if (path === "/api/accounts/search" && req.method === "GET") {
      if (!me) return json(res, 401, { error: "Sign in to find accounts." }), true;
      const q = (url.searchParams.get("q") ?? "").trim().toLowerCase();
      if (!/^[a-z0-9_-]{1,24}$/.test(q)) return json(res, 200, { accounts: [] }), true;
      const found = [...ids.entries()]
        .filter(([name, id]) => id !== me.id && name.toLowerCase().startsWith(q))
        .sort(([a], [b]) => a.localeCompare(b))
        .slice(0, 10)
        .map(([username, id]) => ({ id, username }));
      return json(res, 200, { accounts: found }), true;
    }
    if (path === "/api/library" && req.method === "GET") {
      if (!me) return json(res, 401, { error: "Sign in first." }), true;
      const own = [...designs.values()].filter((d) => d.ownerId === me.id).map(listRow);
      const shared = [...designs.values()]
        .filter((d) => !d.deletedAt && d.members.includes(me.id))
        .map((d) => ({ ...listRow(d), ownerName: nameOf(d.ownerId) }));
      return json(res, 200, { designs: own, shared }), true;
    }
    const m = /^\/api\/library\/designs\/([^/]+)(\/members(?:\/([^/]+))?)?$/.exec(path);
    if (!m) return false;
    if (!me) return json(res, 401, { error: "Sign in first." }), true;
    const ownerId = url.searchParams.get("owner") ?? me.id;
    const key = `${ownerId}:${m[1]}`;
    const d = designs.get(key);
    const role = d && d.ownerId === me.id ? "owner" : d && d.members.includes(me.id) ? "editor" : null;
    if (m[2]) {
      if (!d || d.deletedAt || !role) return json(res, 404, { error: "Plan not found." }), true;
      const sharing = () => ({ ownerId: d.ownerId, ownerName: nameOf(d.ownerId), role, members: member(d) });
      if (!m[3] && req.method === "GET") return json(res, 200, sharing()), true;
      const target = m[3];
      if (req.method === "PUT") {
        if (role !== "owner") return json(res, 403, { error: "Only the owner can share this." }), true;
        if (target === me.id) return json(res, 400, { error: "That is your own plan." }), true;
        if (!nameOf(target) || nameOf(target) === "someone") return json(res, 404, { error: "No such account." }), true;
        if (d.members.length >= 20 && !d.members.includes(target))
          return json(res, 400, { error: "A plan can be shared with at most 20 accounts." }), true;
        const added = !d.members.includes(target);
        if (added) d.members.push(target);
        console.log(`  shared ${key} with ${nameOf(target)}`);
        membersChanged(key);
        if (added) toAccount(target, { t: "shared", doc: key, owner: { id: d.ownerId, username: nameOf(d.ownerId) }, name: d.row.name ?? "A plan" });
        save();
        return json(res, 200, sharing()), true;
      }
      if (req.method === "DELETE") {
        if (role !== "owner" && target !== me.id) return json(res, 403, { error: "Only the owner can unshare this." }), true;
        d.members = d.members.filter((id) => id !== target);
        console.log(`  ${target === me.id ? `${nameOf(target)} left` : `unshared from ${nameOf(target)}`} ${key}`);
        membersChanged(key, target);
        if (target !== me.id) toAccount(target, { t: "unshared", doc: key });
        save();
        return json(res, 200, { ok: true }), true;
      }
      return json(res, 405, { error: "No." }), true;
    }
    if (req.method === "GET") {
      if (!d || d.deletedAt || !role) return json(res, 404, { error: "Plan not found." }), true;
      const room = rooms.get(key);
      return json(res, 200, { design: listRow(d), plan: room?.plan ?? d.plan, ownerId: d.ownerId, role, seq: room?.seq ?? d.seq, live: Boolean(room) }), true;
    }
    if (req.method === "PUT") {
      const b = await body(req);
      if (d && !role) return json(res, 403, { error: "That isn't yours." }), true;
      if (!d && ownerId !== me.id) return json(res, 404, { error: "Plan not found." }), true;
      if (b.plan && rooms.get(key)?.conns.size) return json(res, 409, { live: true }), true;
      const { plan, ...row } = b;
      const next = d ?? { ownerId, id: m[1], row: {}, plan: {}, seq: 0, members: [] };
      next.row = role === "editor" ? { ...next.row, name: row.name ?? next.row.name } : { ...next.row, ...row };
      if (plan) next.plan = plan;
      next.deletedAt = undefined;
      designs.set(key, next);
      save();
      return json(res, 200, { design: listRow(next) }), true;
    }
    if (req.method === "DELETE") {
      if (!d || role !== "owner") return json(res, 403, { error: "Only the owner can delete this." }), true;
      d.deletedAt = new Date().toISOString();
      save();
      kickAll(key, "deleted");
      return json(res, 200, { ok: true }), true;
    }
    return json(res, 405, { error: "No." }), true;
  }

  // endregion

  // region The live service

  /** Rooms by doc key: { plan, seq, conns: Set }; a connection: { id, userId, username, client, send, rooms }. */
  const rooms = new Map();
  const person = (c) => ({ id: c.userId, username: c.username, color: colorFor(c.userId), client: c.client, conn: c.id });
  const present = (room) => [...room.conns].map(person);
  /** Every open socket, so an account hears of a plan added to it or taken from it whatever room it is in. */
  const sockets = new Set();
  const toAccount = (userId, frame) => {
    const text = JSON.stringify(frame);
    for (const c of sockets) if (c.userId === userId) c.send(text);
  };
  const toRoom = (room, frame, except) => {
    const text = JSON.stringify(frame);
    for (const c of room.conns) if (c !== except) c.send(text);
  };

  function open(key) {
    let room = rooms.get(key);
    if (!room) {
      const d = designs.get(key);
      room = { key, plan: d?.plan ?? {}, seq: d?.seq ?? 0, conns: new Set() };
      rooms.set(key, room);
    }
    return room;
  }

  function join(c, key) {
    const d = designs.get(key);
    if (!d || d.deletedAt || !(d.ownerId === c.userId || d.members.includes(c.userId))) {
      c.send(JSON.stringify({ t: "error", doc: key, code: "forbidden", msg: "Not shared with you." }));
      return;
    }
    const room = open(key);
    room.conns.add(c);
    c.rooms.add(key);
    c.send(JSON.stringify({
      t: "snapshot", doc: key, seq: room.seq, plan: room.plan, role: d.ownerId === c.userId ? "owner" : "editor",
      owner: { id: d.ownerId, username: nameOf(d.ownerId) }, members: member(d), present: present(room),
    }));
    toRoom(room, { t: "present", doc: key, present: present(room) }, c);
    console.log(`  ${c.username} (${c.client}) joined ${key}: ${room.conns.size} here`);
    // The stand-ins open what is shared with them.
    if (!c.bot) for (const b of BOTS) if (d.members.includes(ids.get(b.name)) || d.ownerId === ids.get(b.name)) botJoin(b, key);
  }

  function leave(c, key) {
    const room = rooms.get(key);
    c.rooms.delete(key);
    if (!room || !room.conns.delete(c)) return;
    if (![...room.conns].some((x) => !x.bot)) {
      // Nobody real left: the stand-ins go too, and the plan is kept.
      room.conns.clear();
      const d = designs.get(key);
      if (d) {
        d.plan = room.plan;
        d.seq = room.seq;
        save();
      }
      rooms.delete(key);
      return;
    }
    toRoom(room, { t: "present", doc: key, present: present(room) });
  }

  function membersChanged(key, removed) {
    const room = rooms.get(key);
    const d = designs.get(key);
    if (!room || !d) return;
    toRoom(room, { t: "members", doc: key, members: member(d) });
    if (removed) for (const c of [...room.conns]) if (c.userId === removed) {
      c.send(JSON.stringify({ t: "kicked", doc: key, reason: "unshared" }));
      leave(c, key);
    }
    for (const b of BOTS) if (d.members.includes(ids.get(b.name))) botJoin(b, key);
  }

  function kickAll(key, reason) {
    const room = rooms.get(key);
    if (!room) return;
    for (const c of [...room.conns]) {
      c.send(JSON.stringify({ t: "kicked", doc: key, reason }));
      leave(c, key);
    }
  }

  /** Applies a batch to a plan (docs/design/cloud-live.md, Ops): put, patch, del, meta. */
  function apply(plan, ops) {
    const p = { ...plan };
    for (const op of ops) {
      if (op.o === "meta") {
        Object.assign(p, op.f ?? {});
        for (const k of op.d ?? []) delete p[k];
        continue;
      }
      const list = [...(p[op.c] ?? [])];
      p[op.c] = list;
      const i = list.findIndex((t) => t && t.id === op.id);
      if (op.o === "put") i >= 0 ? (list[i] = op.v) : list.push(op.v);
      else if (op.o === "del" && i >= 0) list.splice(i, 1);
      else if (op.o === "patch" && i >= 0) {
        const t = { ...list[i], ...(op.f ?? {}) };
        for (const k of op.d ?? []) delete t[k];
        list[i] = t;
      }
    }
    return p;
  }

  function frame(c, f) {
    switch (f.t) {
      case "hello":
        c.client = f.client === "web" ? "web" : "game";
        return c.send(JSON.stringify({ t: "welcome", v: 1, you: { id: c.userId, username: c.username, color: colorFor(c.userId) } }));
      case "ping":
        return c.send('{"t":"pong"}');
      case "join":
        return join(c, f.doc);
      case "leave":
        return leave(c, f.doc);
      case "ops": {
        const room = rooms.get(f.doc);
        if (!room || !room.conns.has(c)) return c.send(JSON.stringify({ t: "error", doc: f.doc, cid: f.cid, code: "forbidden", msg: "Join first." }));
        room.plan = apply(room.plan, f.ops ?? []);
        room.seq += 1;
        const d = designs.get(f.doc);
        if (d) {
          d.plan = room.plan;
          d.seq = room.seq;
          save();
        }
        return toRoom(room, { t: "ops", doc: f.doc, seq: room.seq, by: c.userId, cid: f.cid, ops: f.ops ?? [] });
      }
      case "presence": {
        const room = rooms.get(f.doc);
        if (!room || !room.conns.has(c)) return;
        c.heard = (c.heard ?? 0) + 1;
        c.last = { cursor: f.cursor ?? null, sel: f.sel ?? [], view: f.view };
        if (c.heard % 50 === 1) console.log(`  presence from ${c.username}: cursor ${JSON.stringify(f.cursor)}, ${f.sel?.length ?? 0} selected, view ${JSON.stringify(f.view)}`);
        return toRoom(room, { t: "presence", doc: f.doc, who: c.userId, conn: c.id, cursor: f.cursor ?? null, sel: f.sel ?? [], ...(f.view ? { view: f.view } : {}) }, c);
      }
      default:
    }
  }

  // The WebSocket itself: the handshake, then text frames both ways (a client's are masked; the server's are not).
  server.on("upgrade", (req, socket) => {
    const url = new URL(req.url, "http://localhost");
    const me = who(req);
    if (url.pathname !== "/live" || !me) {
      socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
      return;
    }
    const accept = createHash("sha1").update(req.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    const c = {
      id: randomUUID().slice(0, 8), userId: me.id, username: me.name, client: "game", rooms: new Set(),
      send: (text) => socket.writable && socket.write(wsFrame(0x1, Buffer.from(text))),
    };
    sockets.add(c);
    let buffered = Buffer.alloc(0), parts = [];
    socket.on("data", (chunk) => {
      buffered = Buffer.concat([buffered, chunk]);
      for (;;) {
        const f = readFrame(buffered);
        if (!f) break;
        buffered = buffered.subarray(f.length);
        if (f.opcode === 0x8) return socket.end(wsFrame(0x8, Buffer.alloc(0)));
        if (f.opcode === 0x9) socket.write(wsFrame(0xa, f.payload));
        if (f.opcode === 0x1 || f.opcode === 0x0) {
          parts.push(f.payload);
          if (!f.fin) continue;
          const text = Buffer.concat(parts).toString("utf8");
          parts = [];
          try {
            frame(c, JSON.parse(text));
          } catch (e) {
            console.log(`  a frame from ${c.username} did not read: ${e}`);
          }
        }
      }
    });
    const gone = () => {
      sockets.delete(c);
      for (const key of [...c.rooms]) leave(c, key);
    };
    socket.on("close", gone);
    socket.on("error", gone);
  });

  // endregion

  // region The stand-ins

  /** A stand-in opens a plan: present, and wandering over its cards with a card or two selected. */
  function botJoin(b, key) {
    const room = open(key);
    const userId = ids.get(b.name);
    if ([...room.conns].some((c) => c.userId === userId)) return;
    const c = { id: `bot-${b.name}`, userId, username: b.name, client: b.client, rooms: new Set(), bot: true, send: () => {} };
    join(c, key);
    const started = Date.now(), phase = b.name === "dee" ? 0 : 2.1;
    const timer = setInterval(() => {
      if (!room.conns.has(c)) return clearInterval(timer);
      const t = (Date.now() - started) / 1000;
      const nodes = room.plan.nodes ?? [];
      const xs = nodes.map((n) => n.position?.x ?? 0), ys = nodes.map((n) => n.position?.y ?? 0);
      const x0 = Math.min(0, ...xs), x1 = Math.max(400, ...xs.map((x) => x + 380));
      const y0 = Math.min(0, ...ys), y1 = Math.max(300, ...ys.map((y) => y + 300));
      const cursor = {
        x: Math.round((x0 + x1) / 2 + ((x1 - x0) / 2) * Math.sin(0.4 * t + phase)),
        y: Math.round((y0 + y1) / 2 + ((y1 - y0) / 2) * Math.sin(0.27 * t + 2 * phase)),
      };
      const pick = Math.floor(t / 4);
      const sel = nodes.length ? [nodes[pick % nodes.length].id] : [];
      toRoom(room, { t: "presence", doc: key, who: userId, conn: c.id, cursor, sel }, c);
    }, 100);
  }

  // endregion

  return { rest };
}

/** One WebSocket frame from the server: final, unmasked. */
function wsFrame(opcode, payload) {
  const n = payload.length;
  const head = n < 126 ? Buffer.from([0x80 | opcode, n])
    : n < 65536 ? Buffer.from([0x80 | opcode, 126, n >> 8, n & 0xff])
    : Buffer.concat([Buffer.from([0x80 | opcode, 127]), (() => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(n)); return b; })()]);
  return Buffer.concat([head, payload]);
}

/** One frame from a client, or null until all of it has come: { fin, opcode, payload, length }. */
function readFrame(buf) {
  if (buf.length < 2) return null;
  const fin = (buf[0] & 0x80) !== 0, opcode = buf[0] & 0x0f, masked = (buf[1] & 0x80) !== 0;
  let n = buf[1] & 0x7f, at = 2;
  if (n === 126) {
    if (buf.length < 4) return null;
    n = buf.readUInt16BE(2);
    at = 4;
  } else if (n === 127) {
    if (buf.length < 10) return null;
    n = Number(buf.readBigUInt64BE(2));
    at = 10;
  }
  const mask = masked ? buf.subarray(at, at + 4) : null;
  if (masked) at += 4;
  if (buf.length < at + n) return null;
  const payload = Buffer.from(buf.subarray(at, at + n));
  if (mask) for (let i = 0; i < n; i++) payload[i] ^= mask[i & 3];
  return { fin, opcode, payload, length: at + n };
}

/** A request's JSON body. */
function body(req) {
  return new Promise((resolve) => {
    let s = "";
    req.on("data", (c) => (s += c));
    req.on("end", () => {
      try {
        resolve(s ? JSON.parse(s) : {});
      } catch {
        resolve({});
      }
    });
  });
}
