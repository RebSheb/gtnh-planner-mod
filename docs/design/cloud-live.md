# Cloud plans, live co-editing and task boards

The owner's call (2026-10-14): plans live in the player's account, saved on every edit and the same on every
device; a plan can be shared with other accounts and edited together live, with everyone's cursor on the board; a
task board (Jira, kept simple) is just a plan shown as a board. The game and gtnhplanner.com edit the same plans the
same way. Signed out, the game works exactly as before: plans on this computer.

This file is the contract both repositories build against: the game (`jackwrichards/gtnh-planner-mod`) and the
website (`jackwrichards/gtnh-factory-flow`, branch `cloud-live`, worktree `C:\Users\jack\ff-cloud`).

## Ground rules

- Release nothing. The website's `main` is what the owner deploys from: all website work stays on `cloud-live` until
  the rollout. Never touch the droplet, Cloudflare or the production Supabase project; the rollout is a separate,
  supervised step at the end (see Rollout).
- Never copy the website's `.env.local` into a worktree: it points at a real Supabase project. Local work runs on the
  local database (see Local development).
- Never lose a plan. A game plan that goes to the cloud keeps its local save untouched as a backup.
- Identity is the website's accounts only (`community_users`: id, username). No Minecraft identity is linked. The
  game helps pick people: signed-in players in the same game come first, then a search of every account.
- Mobile is not a priority.
- Players never see the website's name in the game (as before): it is "your account", "the library", "plans".

## What players see

- **My plans** (game and website): every plan in your account, marked with the faces (initials, coloured) of who
  else is in it. No save button: every edit is saved as you make it. Make a copy replaces Save as. Signed in, every
  plan is in the account from the moment it exists (a new plan, a new task board, one imported, pasted, shared in chat,
  from the library or from NEI's plan button), empty ones too; there is no upload step.
- **Shared with me** (game and website): the Library's shelf beside My plans and Public setups, always there when signed
  in ("Nothing shared with you yet" when empty), each plan's tile naming its owner. When an account is added to a plan
  it is told at once (`shared` below): a notice on the board ("<owner> shared <plan> with you", with Open), or with the
  game's planner shut a chat line with a clickable [Open].
- **Share**: invite accounts as editors. Everyone with the plan open sees the others' cursors (name tag, colour) and
  what they have selected, and their edits as they make them.
- **Publish**: posts a one-time public copy to the library (today's "Share to library"). Update in library refreshes
  it. Publishing never shares the live plan.
- **Task board**: its own kind of plan (`primaryView: "tasks"`, made with the + menu's New task board, or from "Add to
  a task" on cards). It always shows its tasks and never cards, and a normal plan never shows tasks. Columns Blocked,
  To do, Doing, Done (left to right); a task has a title, text (its notes), assignees (members of the plan), a list of **things** and
  comments (the owner, 2026-10-15: "like Jira"; no separate cards section and checklist). Every thing has its own
  checkbox and is one of:
  - a **machine**: a card on a plan (`link`), "4× Electric Blast Furnace (HV)"; a click opens that plan with the card
    centred and selected, or says "That plan is gone";
  - a **location**: a spot in a world (`place`: x y z, dimension, the world it belongs to, an optional label); in the
    game a key on it shows where it is (a beam on the block, its name and distance) and its line says how far and which
    way it is from the player, or which dimension or world it is in;
  - a **screenshot** (`picture`; made in the game only: the planner steps aside, the world is shot without the GUI and
    the planner comes back with it attached): a thumbnail in the list, big on a click.
  - a **quest** (`quest`; the game only, with its quest book: picked in the real book, it ticks itself when the player
    has done it, a click opens it in the book): the website shows it as its words and keeps it as it came.
  Cards go into a task from a card's pin key (Place in the world, Pin to the screen, **Add to a task**) or a right
  click on selected cards ("Add to a task"): a menu of your task boards (and New task board), then of the board's tasks
  with their status dots (and New task); one machine thing per card, with how many to build from the solve. The task
  box (the website's task drawer) is laid out as Jira's issue view: a main column (title, notes, things with their
  add keys, the comment thread, the comment box at its foot) and a side column (Status as a picker, People, the board,
  who made it and when, Delete task at its foot); narrow, the side column goes under the title. A task's card on the
  board shows its title, faces, things done of all of them ("3/5"), how many comments, and its first screenshot. A
  card on a plan shows the dot of the most pressing task that names it (a machine thing, or an older task's link),
  from the task boards loaded.
- The game's top bar is the account's: plan tabs and +, live status and faces, Library, Share, the account key
  ("Sign in" signed out; signed in the account's face, whose menu has the username and Sign out), feedback, settings,
  help. What edits or views the board (Non-recipe, undo, redo, Arrange, Fit, the rate unit, EU/t, Avg/Peak) is the
  toolbar along the top of the board; a task board shows its own header (title, New task) there instead.
- Signed out: My plans is this computer's plans, as today; Share is greyed out with "Sign in to share".

## Accounts and auth

- Session: the website's `gtnh_session` cookie (`<userId>.<expiresMs>.<hmac>`, HMAC-SHA256 with
  `COMMUNITY_HASH_SALT`, `src/lib/server/community.ts`). The browser sends it itself. The game keeps it (as it does now,
  `library/Account`) and sends `Cookie: gtnh_session=<token>` on every request, the live socket's upgrade included.
- The live service verifies the same token with the same secret (shared module, constant-time compare).

## Cloud plans (database)

A cloud plan is a `library_designs` row, addressed everywhere as `<ownerId>:<designId>` (the "doc key"). Additive
schema changes only (the live site keeps running on the old code while they are applied):

```sql
alter table library_designs add column if not exists seq bigint not null default 0;
alter table library_designs add column if not exists live_lease_until timestamptz;
alter table library_designs add column if not exists live_lease_token text;
create table if not exists library_design_members (
  owner_id uuid not null references community_users(id) on delete cascade,
  design_id text not null,
  member_id uuid not null references community_users(id) on delete cascade,
  role text not null default 'editor' check (role in ('editor')),
  added_at timestamptz not null default now(),
  primary key (owner_id, design_id, member_id),
  foreign key (owner_id, design_id) references library_designs(user_id, id) on delete cascade
);
create index if not exists library_design_members_member on library_design_members(member_id);
```

- `seq` counts applied edit batches; the live service writes it with each flush.
- `live_lease_until`: while it is in the future the live service holds the plan in memory and is its only writer. The
  REST plan PUT refuses a plan body then (409 `{ "live": true }`); metadata (name, folder, order, favorite, closed)
  still goes through REST.
- Shared plans count against the owner's limits only.

## REST additions (website)

All signed-in, cookie auth, rate-limited like the existing library routes.

- `GET /api/library` adds `shared`: plans shared with me, `{ ownerId, ownerName, id, name, icon, updatedAt,
  planUpdatedAt, members }`, and each own design gains `members` (ids and usernames).
- A design id is unique only per owner, so every design-scoped route below takes `?owner=<ownerId>` (absent: the
  caller's own design). `GET /api/library` lists tombstones (deleted designs) as it does today, so a client never
  reuses a deleted id.
- `GET /api/library/designs/[id]?owner=<ownerId>`: an owner's or member's read of the plan: `{ design, plan, ownerId,
  role, seq, live }`.
- A member may PUT a shared plan's body and name only; folder, order and the rest stay the owner's. Deleting a
  design is the owner's alone (403 for members).
- `GET /api/library/designs/[id]/members` answers `{ ownerId, ownerName, role, members }`;
  `PUT /api/library/designs/[id]/members/[userId]` (owner only), `DELETE /api/library/designs/[id]/members/[userId]`
  (owner, or the member leaving). At most 20 members a plan. Changes are pushed to the live service (internal call,
  below) so open rooms update and a removed member is dropped.
- `GET /api/accounts/search?q=<prefix>`: `{ accounts: [{ id, username }] }`, up to 10, signed-in only, rate-limited.
- Deleting a design also tells the live service (members are dropped from the room).
- The new table has row level security on, like every other table (only the service role reads it).

## The live service

A separate Node process beside Next.js (`next start` cannot hold WebSockets): the `ws` package, one process, rooms in
memory. Production: systemd unit `gtnh-live` on 127.0.0.1:3001, Caddy routes `/live` to it (WebSocket upgrade), behind
Cloudflare (pings every 25 s keep its 100 s idle limit away). Local: `ws://localhost:3001/live`. The website reads its
URL from `NEXT_PUBLIC_LIVE_URL`, best set to the path `/live` (resolved against the page's own host, so one build serves
staging and, after the switch, the main domain); unset, the website has no live features and behaves as today (so
merging to `main` before the service runs is safe). Exactly one live service runs per database: it takes leases
without asking.

- Limits: 200 frames/s, 30 batches/s, 10 joins/s per connection; 8 rooms an account across its sockets and 500 in
  the service; 16 sockets an account; upgrades limited per account and per address; 1000 ops and 256 KB (UTF-8) a
  batch, 512 KB a frame (bigger closes the socket, 1009); 250 ms of batch work a second an account; 8 MB unflushed a
  room (refused as `rate`); JSON nested at most 64 deep. `GET /live/health`; `POST /internal/shutdown` (token)
  flushes and exits.
- The lease is owned: `live_lease_token` is the room's, every lease write is conditional on it, and a REST save
  clears it. A room that lost its lease stops writing and closes its sockets with 1012.
- Close codes: 1012 = reconnect, rejoin and resend pending; 1013 = try again later (not a sign-in problem); an
  upgrade can answer HTTP 429 (back off). A `rate` error on a batch means send it again later; on a join, retry after
  a few seconds. An echo may carry corrective ops beyond what was sent (the stored thing is the schema's output), and
  the server stamps a task's `createdBy`: clients apply the echo's ops, not their own copy.
- Empty rooms stay open 60 s; whole-plan writes go through one queue (4 at a time) that backs off while the database
  errors.
- A `meta` op may only set plan fields the schema knows and may not delete required ones. The server fills a new
  comment's `by`, `byName` and `at`; only its author may change it, anyone in the plan may delete it.

- Room per doc key. Opening a room loads the plan from the database, takes the lease, and keeps it renewed (every
  30 s, 90 s ahead). The room flushes the plan, `seq`, `plan_updated_at` and `updated_at` at most every 8 s while
  dirty and when the last person leaves, then lets the lease go. The Supabase instance is small: never a write per
  edit.
- Journal: every accepted batch is appended to a per-room file (`LIVE_JOURNAL_DIR`) before it is broadcast, and the
  file is cleared after a flush. On start the service replays journals over the stored plans, so a crash loses
  nothing.
- Validation: each entity a batch puts or patches must pass that collection's Zod schema afterwards; a batch over
  256 KB, a plan over the upload limit, or more than 30 batches a second from one connection is refused. Server time
  stamps comments (`at`).
- Presence is relayed, never stored, at most 20 a second per connection.
- Internal HTTP on the same port, `POST /internal/{members,deleted}` with `LIVE_INTERNAL_TOKEN`, from the Next.js
  routes. `GET /live/nearby?names=<tab-list names, comma-separated, at most 100>` (cookie auth, rate-limited):
  signed-in accounts connected from the same game server as me (the `game` hash in `hello`) whose `mcName` is one of
  the names I sent, so it only tells me accounts of players I can already see; without names it answers `[]`.

### Wire protocol (JSON text frames, version 1)

Client to server:

| Frame | Meaning |
|---|---|
| `{ t:"hello", v:1, client:"game"\|"web", clientVersion, game?:{ server:"<sha256 of address or world>", mcName } }` | first frame; answered with `welcome` |
| `{ t:"join", doc }` | open a room; answered with `snapshot` |
| `{ t:"leave", doc }` | |
| `{ t:"ops", doc, base, cid, ops:[Op] }` | one local edit; `base` = the seq the client had; `cid` = client batch id |
| `{ t:"presence", doc, cursor:{x,y}\|null, sel:[ids], view?:{x,y,zoom} }` | board coordinates (the plan's own) |
| `{ t:"ping" }` | every 25 s |

Server to client:

| Frame | Meaning |
|---|---|
| `{ t:"welcome", v:1, you:{ id, username, color } }` | |
| `{ t:"snapshot", doc, seq, plan, role:"owner"\|"editor", owner:{id,username}, members:[{id,username}], present:[Person] }` | the whole plan |
| `{ t:"ops", doc, seq, by, cid, ops }` | an accepted batch, to everyone in the room including its sender (the sender's ack is its own `cid`); a batch sent again with a `cid` already applied is acked to its sender alone as `{ ops: [], seq: <original> }` |
| `{ t:"presence", doc, who, conn, cursor, sel, view? }` | |
| `{ t:"present", doc, present:[Person] }` | someone joined or left; `Person = { id, username, color, client, conn }` (one account can be in a room twice: `conn` tells them apart) |
| `{ t:"members", doc, members }` | sharing changed |
| `{ t:"kicked", doc, reason:"unshared"\|"deleted" }` | |
| `{ t:"shared", doc, owner:{id,username}, name }` | this account was added to a plan: sent to **every open socket of the account**, in a room or not, each time it is added; the client asks the list again at once and tells the player |
| `{ t:"unshared", doc }` | this account was taken off a plan (or left it): to every open socket of the account; a client in its room also gets `kicked` |
| `{ t:"error", doc?, cid?, code:"auth"\|"forbidden"\|"not_found"\|"too_big"\|"rate"\|"bad_op"\|"version", msg }` | `cid` when it is about a batch |
| `{ t:"pong" }` | |

Colours: the server gives each account a colour from a fixed palette, stable per account (hash of id).

### Ops

The plan is the website's `FactoryProject`. Its arrays of things with an `id` are collections: `recipes`, `nodes`,
`storages`, `edges`, `annotations`, `pockets`, `fuelProfiles`, `productionGroups`, `tasks`, `taskItems`,
`taskComments` (and any later array of `{ id }`). Every other top-level field is meta.

```
{ o:"put",   c, id, v }            insert or replace the whole thing
{ o:"patch", c, id, f, d? }        set top-level fields f, delete fields d (names); ignored if the thing is gone
{ o:"del",   c, id }               no-op if already gone
{ o:"meta",  f, d? }               set / delete top-level plan fields
```

- The server applies batches in arrival order and gives each a `seq`. Later writes to the same field win
  (Figma's rule). Collection order is not meaningful; a new thing is appended.
- Clients send only what a person did: derived data (solve results, runtime tables, anything recomputed on load) is
  never in an op. Each client strips the same derived fields before diffing.

### The client algorithm (game and website alike)

- Keep `confirmed` (the plan at the last seq heard) and `pending` (own batches not yet echoed back). What the editor
  shows is `confirmed` with `pending` applied on top.
- A local edit: diff the plan before and after it (things by id; changed top-level fields as a patch; meta), send the
  batch, add it to `pending`, record its inverse for undo.
- A batch from someone else: apply to `confirmed`, re-apply `pending`, show the result. Own echo: apply to
  `confirmed`, drop it from `pending`.
- Reconnect: join again, take the snapshot as `confirmed`, send `pending` again.
- Undo undoes only your own edits: it applies the recorded inverse as a new edit (the fields you changed go back to
  what they were). If someone changed the same field since, undo overwrites it, as in Figma.
- Offline: edits wait in `pending` (kept on disk in the game, IndexedDB on the website) and go when the connection
  comes back. A plan opened offline is the last copy this device saw.

## Plan document additions (both sides)

`FactoryProject` and its schema gain, all optional (the Zod schemas strip unknown keys, so each must be declared):

- `gtnhplanner?: Record<string, unknown>` on the project, nodes, storages, edges, annotations and pockets: the game's
  own data for that thing, opaque to the website, kept as is through every website edit (see Game mapping). At most
  64 KB per thing.
- `primaryView?: "plan" | "tasks"`: which view the plan opens on. A task board is a plan with `"tasks"`.
- `tasks?: PlanTask[]`: `{ id, title (1-120), text? (≤4000), status: "todo"|"doing"|"blocked"|"done", order: string
  (fractional index), assignees?: string[] (account ids), links?: PlanLink[], createdBy?, createdAt?, doneAt?,
  priority?: "high"|"medium"|"low", movedAt? (ISO: when it last changed column), blockedBy?: string[] (ids of tasks
  on the same board it waits on) }`. No `priority` is Medium (the game
  writes none for Medium). `movedAt` is stamped by whoever moves the task to another column (a move within a column
  leaves it); a task without one counts from `createdAt`. Cards show a priority arrow (up high, bar medium, down low)
  and one dot a day in the column to four, amber from 7 days, red from 14, none in Done. **The website's schema must
  accept `priority` and `movedAt`.**
- `blockedBy` (the owner, 2026-10-15): "Blocked by", a description that stays whatever column the task is in. Each id
  once, never the task itself, never a loop (a task cannot wait on one that waits on it, directly or down the chain:
  the adding side refuses it; a loop two people close at once is drawn broken, never an error). No `blockedBy` is
  none (the game writes none for an empty list). Deleting a task takes its id out of every other task's list in the
  same edit; an id naming no task on the board is ignored. The task box shows it as Waiting on (add, remove) and its reverse as Unblocks (read
  only); cards on the board show nothing of it. The chain view (Board / Chain in the board's header, remembered per
  board on each device, never synced) draws the tasks as the quest book does, kept plain: layers left to right, arrows
  from each task to the tasks it unblocks, each node its title, priority, faces and its column's colour, done tasks
  dimmed. **The website's schema must accept `blockedBy`.**
- `PlanLink`: `{ plan: string, node: string }`: a card on a plan, `plan` the plan's id (its doc key
  `<ownerId>:<designId>`, or a local id for a plan not in an account) and `node` the card's id in that plan, as that
  plan spells it. The older `nodeIds` (task) and `nodeId` (item) named cards of the plan holding the task: still read
  (as links to the plan holding them), never written.
- `taskItems?: PlanTaskItem[]`: a task's things, separate so two people can tick two at once: `{ id, taskId,
  kind?: "machine"|"location"|"picture"|"quest", text (1-200), done: boolean, order: string, link?: PlanLink,
  place?: PlanPlace, picture?: PlanPicture, quest?: PlanQuest }`. `text` is always written, the thing in words ("4× Electric Blast Furnace
  (HV)", "Ore field at 120 64 -30 in Overworld", "Screenshot"), so a reader that knows only lines shows something
  true. An item with no `kind` is an older checklist line: a machine when it has a `link`, else plain words with its
  checkbox; it is written back without a kind. **The website's schema must accept `kind`, `place` and `picture`**
  (its Zod schemas strip unknown keys: undeclared, a game's spot or screenshot is lost on the first website edit).
- `PlanQuest`: `{ id: string (BetterQuesting's quest UUID, the same in every world of a pack), name: string (≤200),
  icon?: string (an item key, `item:<mod>:<name>:<meta>`), line?: string (its quest line's name) }`: the game's only;
  the website accepts kind "quest" and keeps `quest` untouched (an unknown kind would refuse the whole live batch).
- `PlanPlace`: `{ x, y, z: integer (the block), dim: integer, dimName: string (as the game names it: "Overworld",
  "Nether"), world: string (as placed machines key worlds: `server:<16 hex>` or `world:<uuid>`), label?: string
  (≤60) }`.
- `PlanPicture`: `{ thumb: string (a JPEG data URL, 320 px wide, the game keeps it under 40 KB), w, h: integer (the full
  shot's size), takenAt: string (ISO time), file?: string (the full shot's file name in the screenshots folder of the
  game that took it; only that game can show it big, everyone else sees the thumbnail) }`. Only the thumbnail syncs.
- `taskComments?: PlanTaskComment[]`: `{ id, taskId, by (account id), byName, at (server time), text (1-2000) }`

## Ops in detail (both engines must agree exactly)

- Collections are a fixed list, `LIVE_COLLECTIONS`, defined in the website's `src/lib/live/ops.ts` and mirrored in the
  game; changing it bumps the protocol version. An absent collection equals an empty one.
- Equality is JSON equality with numbers compared by value (`1` equals `1.0`), objects by keys regardless of order,
  arrays in order. A field set to its absent state is a delete (`d`), never `null`.
- A diff of two plans: for each collection, things only in the new plan are `put`, only in the old are `del`, in both
  with any top-level field different are `patch` (changed fields in `f`, removed fields in `d`); then one `meta` for
  changed top-level plan fields. Order inside a batch: puts, patches, dels, meta.
- The inverse of a batch, computed against the plan before it: `put` becomes `del`, `del` becomes `put` of the old
  thing, `patch` becomes a `patch` restoring the old values of exactly those fields (deleting the ones that were
  absent), `meta` likewise.
- Both engines ship the same test vectors (`live-vectors.json`: plan, batch, expected plan; plan pair, expected diff),
  checked in on each side.

## Game mapping

The game keeps its own model (`Graph`) and maps it to and from the shared plan, losslessly for anything the game
made (`cloud/map/CloudPlans`; the engine is `cloud/sync`). Since the website's one-mode board (its `live-backport`,
2026-10-15) the plan is a plan-mode plan both sides edit the same things in: cards, drawers (buffers too), wires,
every annotation kind, groups (pockets), port rates, setting pins and leftover splits.

- Ids: the game's UUID strings are the plan's ids (as `PlanExport` already writes them), and a canonical UUID string
  reads back as that UUID. Any other id (one the website made) keeps its string; the game maps it to a UUID with
  `UUID.nameUUIDFromBytes` and writes the same string back. Drawer-link wires are `<drawer>:<node>:<port>`, a
  buffer's wire to a user `<drawer>:<node>:in:<port>`; a link the website wired keeps its own id. A card's recipe is
  `gtnhplanner:<nodeId>`. A group is its pocket under its UUID; a pocket the website made keeps its id, which the
  group also keeps (`BoardGroup.ffId`) to post under.
- Positions are one to one: the website's board draws the game's card (320 wide, rows of two cells) and drawers (116
  by 48) and places every plan, old ones too, where its positions say, so the plan's coordinates are the board's
  pixels (`PlanExport.position`, `gameX`; the posting export and the importer the same). A website position between two
  pixels reads as the nearest and is written back untouched unless the game moves the thing. Sizes are written to the
  pixel too (a group's frame, a note's, a drawing's): the website draws a shared plan's sizes as they are, and a plan
  it loads from a file or a post grows each to whole cells, which only adds room.
- Groups (`docs/design/groups.md`): a `BoardGroup` is a pocket (`name`, `colorTag`, `expanded`, `size`, `theme`,
  `pattern`), nested by `parentPocketId`; each card, drawer, note and group in one carries its `pocketId`, and every
  position is measured from its pocket's frame corner in the board's pixels (a pocket's from its parent's). The game
  writes each place as the board's pixels from the frame, so a group moved moves only its pocket and everything comes
  back to the pixel; a pocket the website never opened (no `size`) is read with the shift the website gives its
  members when it first opens it, and once the game writes it standing open it gets a size and every member is
  written measured from its frame (`PlanWrite.opened`), as the website does when it opens one. A thing's place is its position and its pocket: both are left out of its `sum`, so
  a card the website drops into a pocket stays the game's own.
- Annotations: sticky notes, boxes, zones, arrows (`arrowHeads`, `arrowWidth`, a level arrow's 0 axis) and pictures
  (kept, never shown) are the board's notes of that kind, both ways (`FfConverter.note`, `PlanExport.note`).
- Buffer drawers (`docs/design/buffer-drawers.md`): a buffer is its storage, a wire into it from each maker and a wire
  from it to each user. Links are told apart by side (an input a drawer feeds, an output that feeds it), never by the
  drawer's kind, so a drawer that becomes a buffer (or stops being one) keeps its wires. A storage the website wires
  both ways reads as a buffer; one wired to another drawer or by a cell wire stays in the plan untouched (the
  importer joins its wires instead). A website buffer's rule is live only in a plan-mode plan.
- Each node, storage, edge, annotation and pocket the game writes carries `gtnhplanner: { v: 1, game, sum }`. `game`
  is the game's own JSON for it: a node's save without its id, place and group; a drawer's without its id, place,
  group, links and users (its wires are both); a wire's `{ srcOut, dstIn }`; a link's `{ port }`; a note's save
  without its id, place and group; a group's without its id, corner and parent (its exact size, name, colour, paper
  and imported website id). `sum` is the first 16 hex digits of the SHA-256 of the thing's website fields (`position`,
  `pocketId`, `parentPocketId` and `gtnhplanner` left out, its id kept) in one spelling: keys sorted, numbers by value,
  so the website reading and writing it again keeps it. The plan's own `gtnhplanner` is `{ v: 1, game: { balanceMode,
  settingPins, spareShares, groups, icon }, sum }`: groups and shared machines are the game's, `icon` the icon's
  resource key, setting pins and spares in the game's spelling, `sum` over the plan's `icon`, `settingPins` and
  `spareShares`.
- The plan's fields the game writes: `solveMode` and `planMode` (always both, so the website never converts a game
  plan; a plan without `planMode` is the website's to convert when it opens it), `name`, `description`, `icon`,
  `primaryView`, and the picks both sides edit (`cloud/map/PlanPins`):
  - `settingPins`: the website keys a machine by its handler id and a power card by `power:<source>`, its settings by
    their ids (`heatingCoil`, a machine option's id, a power setting's id); the game keys a machine by its name
    (`machine:<name>`) and its settings as its sheet does (`machine:coil`, `machine:machine:<id>`, `power:<id>`). Each
    side's pins are written in the other's words where it has them; a pin only the game can spell (a voltage, a
    reading, a key the website has no machine for) stays in the game's data, and a website machine with no card on the
    board is kept on it as `site:<key>` and goes back as it came.
  - `spareShares`: bearer keys `<card>:<i|o>:<port>` with the plan's card ids (a website shared machine's later recipe
    `<card>#r<n>`), a leftover the sorted keys joined by `|`, as both solvers write them.
  While these fields are the ones the game's data was written with, the game reads its own spelling; else the
  website's, translated.
- Cards: `machineCount` is the count the card is set to, `solvePin` that count when it is pinned (the posting export
  writes the solve's answer there instead), `portRates` the rates set on its ports, by the handle the website's rows go
  by (`output:item:<id>`, an input by the item it is set to, a shared machine's section `r<n>:`), a second. A card the
  website changed takes its rates from `portRates`; only rates on ports the website has no row for are kept from the
  game's data.
- Drawers: a source's rule is written as the website writes it (`targetPerSecond` negative, `targetMode`), a product's
  positive, trash as `drainMode: "trash"`, a buffer with no `drainMode` (its wires say what it is).
- Reading: a thing whose `sum` still matches is rebuilt from `game` exactly, taking only its place from the website
  fields. A thing the website made or changed beyond its place is built from the website fields the importer's way
  (`importer/FfCards`, never new ids), keeping from `game` what the website has no place for: world links, rates on
  ports the website has no row for, an unpinned card's count, settings the website does not keep; a drawer keeps its
  kind (when nothing is wired to it) and its resource key (while the website names the same resource); a group its
  imported website id and its exact size while the website's is the one the game wrote. A card whose game data reads
  with no ports (NEI knows its recipe by another id, another pack version) is built the importer's way too.
- What the game has no place for stays in the plan as it was and is not on the board: buffers wired to another drawer
  or by a cell wire, loose cell wires, switched-off cards, recipes the game lacks, fuel profiles, production groups,
  custom rate cards where the game builds none. A wire to a card or drawer the game deletes goes with it.
- A shared machine the website made (`extraRecipes`) reads as the game's shared machine; its later recipes and their
  group come with the card and are written only as part of it: changes to them in the game stay in the game.
- Writing is against the plan read (`CloudPlans.write(graph, game, plan, basis)`; the basis records what each thing
  was read as). A thing nobody changed is written exactly as the plan has it, so a plan read and written straight back
  is unchanged, a website card included. A thing only moved changes only its `position` (and `pocketId` when it changed
  group). A changed thing takes only the website fields its change touched (what the game writes for it now against
  what it wrote for it when read) and then carries `gtnhplanner`. A changed card whose recipe changed gets its own
  `gtnhplanner:<id>` recipe rather than changing one the website made, which other cards may share. A recipe no card
  uses any more after a game edit goes.
- A local edit in the game is `CloudPlans.diff(view, write(board, view, basis))`.
- Derived, stripped from both plans before every diff (`cloud/map/Derived`), never an edit: a power card recipe's
  readings, `recipes[].power.euPerTick`, `.stats` and `.warnings` (the source's model at the card's settings; both
  sides work them out again on load). Nothing the solve answers is written at all.
- Per player, never in the plan: camera, selection, folded sections, the pin list (a cloud plan keeps its pins on this
  device, per plan), when the plan was last open, whether its tab is open, its library post (`postId`). Its
  description and icon are the plan's `description` and `icon`.
- Presence names what is selected by the plan's ids, a group by its pocket's.
- Placed machines (world links) are shared, each keyed by the world it was placed in; a game shows the ones for its
  own world. A card's game data spells them `worlds: { <world>: [[dim, x, y, z, facing, size?], ...] }` (game data
  from before has one `worldLinks` list, read as this world's). A world is `server:<first 16 hex of the SHA-256 of
  NEI's remote/<address>>`, so everyone on one server shares placements, or `world:<uuid>` for a single-player world,
  made the first time and kept as `world-id.txt` beside its plans. Hashed: a shared plan never names anyone's server
  or save. The board's `Node.worldLinks` are this world's; the rest ride in `Node.elsewhere`.
- Tasks are part of `Graph` (`tasks`, `taskItems`, `taskComments`, `primaryView`) for local plans too, saved by
  `Serializer` as the plan spells them (so plan codes carry them), and mapped one to one to the plan's collections.
  A thing's `place` and `picture` are written and read by `Tasks` as the plan spells them (`Tasks.Place`,
  `Tasks.Picture`), so `cloud/map` carries them with no mapping of its own. New machine things name their card on the
  item only; a task's own `links` are an older task's and still read (shown as machines, a loose card counting as a
  thing not done), and taking a machine off a task takes its link too.
  A task's cards (`nodeIds`, a line's `nodeId`) are the board's card ids on the board and the plan's ids in the plan:
  a card the website made is linked by the website's id there, by the UUID it reads as here; an id that is no card
  stays as it is both ways.
- The website's side must keep what the game writes: its live service stores each thing as its schema's output, so a
  field the schema does not declare is dropped. `gtnhplanner` is declared on the project, nodes, storages, edges,
  annotations and pockets (`factoryPocketSchema`; without it a group the website keeps came back to the game built from
  its website fields, its imported id lost). A pocket's `name` must not be empty: the game writes "Group" for a
  nameless group. A card the website copies or pastes drops the original's `gtnhplanner` (its placed machines are
  the original's), and an edit naming a recipe the shared plan lacks carries it (the website's dataset refresh re-keys
  a game card's `gtnhplanner:<card>` recipe on its screen only).
- WebSocket: Minecraft's own netty (`io.netty.handler.codec.http.websocketx`, TLS through the JDK's SSLEngine), so no
  new library ships; the jar still runs on Java 8.
- Undo for a cloud plan applies recorded inverses (above) instead of restoring snapshots; local plans keep today's
  undo.

## The game's session (as built)

`cloud/session`: `CloudSession` (one per signed-in account) and `LiveDoc` (one per cloud plan); the game's side is
`cloud/session/game/CloudHooks` (`Plan.setCloudSource`, the client tick, sign-in and sign-out, the exit flush).

- Cloud plans start only once the account's site has been seen to run the live service (`GET /live/health`, then the
  list), remembered per device (`device.json`'s `cloud`): a site without it (before the rollout, the library's
  stand-in) keeps the world's plans open, signed in or not, so the mod can ship ahead of the website.
- Signed in, `Plan` is the account's cloud plans (`Plan.cloud`, `isCloud`): its list (`CloudApi.library`, kept as
  `list.json`) and this device's copies, opened offline. Tabs, the open plan, the order and when each was open are this
  device's (`device.json` beside the copies), as are pins. `PlanAPI.save` never writes the world's save then: it hands
  every board to its plan. Signed out, nothing changes. Signing in or out reloads the plans (`Plan.reload`).
- A board change is written against the plan the board stands for (not the live copy, which may hold others' edits the
  board has not read yet), so a batch is only what the player did and never brings back what someone else deleted. Each
  is cut below 200 KB and 5,000 ops (`LiveState.local(ops, maxBytes)`, undone as one), kept on disk, then sent. A batch
  leaves the disk only once a kept copy of the confirmed plan holds it, so a crash leaves every edit in the copy or
  waiting.
- Others' batches land on the live copy at once and are read into the board once a tick, only when the board is at
  rest (no wheel spin settling, nothing in hand), reading only what they touched (`CloudPlans.read(plan, game, previous)`).
  Reading never sends anything and never runs the edit-time cleanups.
- Rooms: the open plan's, and any plan with edits still to send (at most 8); others are left after 10 s. A refused
  batch is dropped and the board goes back (`rate` is sent again from the next snapshot instead).
- A plan new to the planner (made, pasted, imported, shared in chat, a library download, a task board, the tour's)
  becomes a new cloud plan at once, empty or not (only a slot that failed to read, and the empty plan an account with
  none opens on while the world's plans have not arrived, wait). It is created with REST (retried until it goes), then
  joined. Renaming is an edit (`meta name`; changing a
  plan's name, description, icon or view moves the board's version like any edit) and also renames the list entry
  (REST, the owner's or, with `?owner=`, a member's). The list's name is the plan's name: the website renames the list
  alone and leaves the plan's `name` as it was, so the game takes a list rename it hears into the board (unless its
  own rename is still on its way), which writes it into the plan too, and reading others' edits takes the plan's
  `name` only when that name itself moved. Deleting asks the account to delete (owner) or leave (member), and drops it
  from this device's copy of the list at once; a plan taken off the planner any other way stays in the account. A plan
  deleted or unshared elsewhere leaves the planner with a notice.
- A world's own plans go up once (`Migration`), against a list fetched this session, after a backup; the world's save
  keeps them and their keys. One notice when any arrived; a plan that failed goes next time. An account with no plans
  yet opens on one empty plan, which the world's plans replace when they arrive (the one the world had open opens).
- The top bar's tab strip ends in the status while signed in: Live, Connecting, or Offline (with ": edits kept" while
  edits wait).
- Presence and sharing build on `BoardSession.live()` (the open plan's `LiveDoc`: `key`, `role`, `owner`, `members`,
  `present`, presence and people listeners, `sendPresence`, `planId`, `at`/`onBoard` for coordinates) and
  `CloudSession.client()`.
- Presence out (`ui/live/PresenceOut`): the board samples the mouse as it draws; while it is on the board itself (not
  a popup or bar over it) the cursor goes in the plan's coordinates, with the plan's ids of the selected cards, drawers
  and notes and the view (`{x, y, zoom}`: the plan's point in the middle of the board, and the board's zoom), at most
  ten a second and only when something changed. The cursor is null off the board, on the task board or the library;
  closing the planner or opening another plan sends no cursor and nothing selected.
- Presence in (`ui/live/Peers`, drawn by `ui/canvas/PresenceLayer`): each other connection's cursor, a small arrow in
  its colour with its name on a tag, one size on screen at every zoom, gliding to where it was last heard and fading
  out once quiet for 9 s; what it has selected outlined in its colour, two people on one thing one ring round the
  other, their names over the first. This account's own other connections are not drawn, as on the website. Nothing
  is drawn, and nothing measurable spent, while nobody is pointing at or holding anything. `call 'cloud?fake=N'`
  (dev/DevPeople) puts stand-ins on the open plan without a live service.
- Faces: the tab strip ends in who else has the open plan open (one face per account, its initials in its colour,
  five and then a count; the tooltip names them and says in game, in a browser or both; none while not in the room,
  offline included, as who is there is not known), then the status. Colours are
  the service's own (`Peers.colourOf` is the website's `colorFor`), task faces included, so one person is one colour
  everywhere. On a cloud plan a task's Assign key lists its owner and members (`TaskLook.people`) and a pick puts them
  on the task or takes them off; a plan on this computer keeps Assign me.
- Being shared with: `shared` and `unshared` reach `LiveClient.Listener`; `CloudSession` asks the list at once and tells
  the host (`Host.shared`): the board's notice with Open (`BoardSession.openPlanByKey`), or with the planner shut a chat
  line whose [Open] runs `/gtnhplanner_open <key>` (`client/OpenPlanCommand`). The Library's Shared with me shelf is the
  plans whose key is another account's.
- Share with people (the top bar's Share menu; always there on a cloud plan, greyed with "Sign in to share" signed out): `ui/popup/SharePanel`, a box in the middle of the screen. Who can edit (the owner, then the members:
  the owner removes them, a member leaves through the tab's own Leave plan), then for the owner the signed-in players in
  this game (`CloudApi.nearby` with the tab list's names, at most 100; asked when the box opens and on Look again,
  never on a timer, as the service limits it) and a search of every account as a name is typed (300 ms after the last
  key). Each shows its username, an in-game one its Minecraft name too. The list follows the room (`members` pushes)
  and `CloudApi.sharing` (`GET .../members`). The library's post is Publish to library (Update in library once
  published): it never shares the live plan.

## Local development

- Website: `npm run dev:local` runs Next.js and the live service against a local database: real Postgres in-process
  (PGlite) loaded from `supabase/schema.sql` plus the migration, behind a PostgREST-compatible `fetch` shim handed to
  supabase-js, so the existing server code runs unchanged. Data in `scratch/local-db/`. No Supabase credentials
  anywhere.
- Game: `call 'library?url=http://localhost:3000'` points the game at it (the live URL follows: `ws://localhost:3001/
  live`, or `call 'library?live=...'`).
- Game alone: `node tools/dev/mock-library.mjs` with `tools/dev/mock-live.mjs` is a stand-in for the site and the live
  service in one process (in memory, kept in `build/dev-client/mock-*.json`), with two stand-in accounts who open a plan
  shared with them and point at its cards: sharing, presence and faces end to end without the website.
- Two people: the dev game plus the website in a browser, or two browser profiles; a headless test client in each
  repository drives the protocol.

## Rollout (last step, with the owner)

1. Apply the migration SQL to production Supabase (additive).
2. Staging at `new.gtnhplanner.com` (the owner's call): a second Next.js instance from `cloud-live` (unit
   `gtnh-flow-next`, 127.0.0.1:3002) and the live service (`gtnh-live`, 127.0.0.1:3001, env `COMMUNITY_HASH_SALT`,
   Supabase keys, `LIVE_INTERNAL_TOKEN`, `LIVE_JOURNAL_DIR`, `LIVE_ALLOWED_ORIGINS`), on the production database. A
   Caddy site block and a proxied Cloudflare DNS record; WebSockets checked through Cloudflare. Session cookies are
   host-only, so staging has its own sign-in (same accounts). The owner tests there with plans they don't mind
   losing; a game test build points at it with `-Dgtnhplanner.library.url=https://new.gtnhplanner.com`.
3. The switch: Caddy sends `gtnhplanner.com` to 3002 and its `/live` to 3001. Rollback is the same line back to 3000.
   Then merge `cloud-live` into the website's `main`, bump the version and changelog, so the next ordinary deploy
   carries it.
4. The mod ships in its next release, as usual.

Each step is reversible on its own: the columns and table are unused by old code; the switch is one Caddy line;
unsetting `NEXT_PUBLIC_LIVE_URL` turns the live features off. The runbook is the website's `docs/live-rollout.md`.
