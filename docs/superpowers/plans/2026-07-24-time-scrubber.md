# Time Scrubber Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the substrate's time dimension visible: a ⏱ scrubber that reconstructs the whole workspace at any past event, read-only, one keypress from "now".

**Architecture:** The Store protocol already has `as-of` (materialize a log prefix) and events carry `:ts`. A new read-only `FrozenStore` wraps an as-of snapshot behind the same protocol, so EVERY existing read payload (state/object/mold/notebook/links) gains time travel through one `?at=N` query param — no payload code changes. A new `/api/events` returns humane labels for the scrubber. Frontend: ⏱ topbar button → bottom scrubber strip; dragging fetches `?at=n` state and re-renders; a `timemode` CSS class hides every write affordance; JS guards make writes toast instead of commit. Scrubbing never touches memory/recall — the scrubber travels the *record*, not the *recall*.

**Tech Stack:** Clojure (substrate + server), vanilla JS shell, cognitect test-runner.

**Decisions (made autonomously; user asleep):** view-only v1 (no "rewind to here" — that's multi-undo, deferred); scrubbing forces overview mode (natural vantage; avoids notebook-fetch races), focusing a panel in the past is allowed read-only; LEAP disabled while in the past; memory pane stays live (recall is not time-scrubbed — thesis: undo/time never touch memory); Esc exits time mode before anything else.

---

### Task 1: substrate — FrozenStore + frozen-at

**Files:**
- Modify: `src/loci/substrate.clj`
- Test: `test/loci/substrate_test.clj`

- [ ] **Step 1: Write the failing tests** (append to substrate_test.clj; it already requires `[loci.substrate :as sub]` and `clojure.test`)

```clojure
(deftest frozen-store-is-a-read-only-window
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
    (sub/commit! st {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
    (sub/commit! st {:op :assoc :id "a" :path [:value] :value "1'"})
    (let [fz (sub/frozen-at st 2)]
      ;; sees exactly the first 2 events
      (is (= "1" (:value (sub/object fz "a"))))          ; edit (event 3) not applied
      (is (= "B" (:title (sub/object fz "b"))))
      (is (= 2 (count (sub/history fz))))
      ;; the present is untouched
      (is (= "1'" (:value (sub/object st "a"))))
      ;; the past cannot be edited
      (is (thrown? UnsupportedOperationException (sub/commit! fz {:op :put :id "c" :value {}})))
      (is (thrown? UnsupportedOperationException (sub/undo! fz)))
      ;; as-of within the window still works (nested time travel)
      (is (nil? (sub/object (sub/frozen-at fz 1) "b"))))))
```

(The nested check works because `frozen-at` only needs `history`, which FrozenStore provides.)

- [ ] **Step 2: Run to verify failure** — `clojure -M:test` → No such var: `sub/frozen-at`.

- [ ] **Step 3: Implementation** (append to substrate.clj, after `persistent-store`)

```clojure
;; ----------------------------------------------------------------------------
;; a read-only window onto the past — the same Store protocol over an `as-of`
;; snapshot, so every reader (mold, notebook, links, payloads) time-travels
;; for free. The past cannot be edited; only viewed.
;; ----------------------------------------------------------------------------

(defrecord FrozenStore [snapshot log-prefix]
  Store
  (commit! [_ _] (throw (UnsupportedOperationException. "read-only: the past cannot be edited")))
  (state   [_] snapshot)
  (objects [_] (:objects snapshot))
  (object  [_ id] (get-in snapshot [:objects id]))
  (history [_] log-prefix)
  (undo!   [_] (throw (UnsupportedOperationException. "read-only: the past cannot be edited")))
  (as-of   [_ n] (materialize (take n log-prefix))))

(defn frozen-at
  "A read-only Store showing the world after the first n events of `st`."
  [st n]
  (let [prefix (vec (take n (history st)))]
    (->FrozenStore (materialize prefix) prefix)))
```

- [ ] **Step 4: Run to verify pass** — `clojure -M:test` → 0 failures (44 tests).
- [ ] **Step 5: Commit** — `git commit -am "feat: FrozenStore — the past as a read-only Store window"`

---

### Task 2: server — ?at= on every read + /api/events

**Files:**
- Modify: `src/loci/server.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest store-at-clamps-and-freezes
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:a" :value {:id "doc:a" :kind :doc :title "A" :value "x"}})
    (sub/commit! st {:op :put :id "doc:b" :value {:id "doc:b" :kind :doc :title "B" :value "y"}})
    ;; nil/garbage → the live store itself
    (is (identical? st (srv/store-at st nil)))
    (is (identical? st (srv/store-at st "garbage")))
    ;; a valid n → frozen prefix
    (is (nil? (sub/object (srv/store-at st "1") "doc:b")))
    (is (= "A" (:title (sub/object (srv/store-at st "1") "doc:a"))))
    ;; clamped: negative → 0 events, huge → all events
    (is (empty? (sub/objects (srv/store-at st "-5"))))
    (is (= "B" (:title (sub/object (srv/store-at st "999") "doc:b"))))))

(deftest events-payload-has-humane-labels
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "Revenue" :value [{:a 1}]}})
    (sub/commit! st {:op :tx :events [{:op :put :id "doc:n" :value {:id "doc:n" :kind :doc :title "Note" :value "hi"}}
                                      {:op :assoc :id "tbl:t" :path [:title] :value "Revenue2"}]})
    (sub/commit! st {:op :delete :id "doc:n"})
    (let [{:keys [total events]} (srv/events-payload st)]
      (is (= 3 total))
      (is (= 3 (count events)))
      (is (= [1 2 3] (map :i events)))
      (is (str/includes? (:label (first events)) "Revenue"))   ; put labels with title
      (is (str/includes? (:label (second events)) "(+1)"))     ; tx aggregates
      (is (str/includes? (:label (nth events 2)) "doc:n")))))  ; delete labels the id

(deftest state-payload-time-travels-via-frozen-store
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:intent "i" :cells []}}})
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (let [past (srv/state-payload (srv/store-at st "1"))]
      (is (= 1 (:events past)))
      (is (= ["space:n"] (map :id (:spaces past))))
      (is (empty? (:objects past))))))
```

(Add `[clojure.string :as str]` to the test ns require if not present.)

- [ ] **Step 2: Run to verify failure** — No such var: `srv/store-at`.

- [ ] **Step 3: Implementation** (in server.clj, near the payload fns)

```clojure
;; ---- time travel: ?at=N freezes any read at a log prefix; /api/events
;; labels the log for the scrubber. The past is read-only by construction. ----
(defn store-at
  "The store to read from: live when at-str is nil/garbage, else a read-only
   frozen window clamped to [0, event-count]."
  [st at-str]
  (if-let [n (and at-str (parse-long at-str))]
    (sub/frozen-at st (-> n (max 0) (min (count (sub/history st)))))
    st))

(defn- title-of [st id] (or (:title (sub/object st id)) id "object"))

(defn- event-label [st {:keys [op id events]}]
  (case op
    :put    (str "＋ " (title-of st id))
    :assoc  (str "✎ " (title-of st id))
    :delete (str "✕ " (or id "object"))
    :tx     (str (event-label st (first events))
                 (when (> (count events) 1) (str " (+" (dec (count events)) ")")))
    (name (or op :event))))

(defn events-payload [st]
  (let [evs (sub/history st)]
    {:total (count evs)
     :events (vec (map-indexed (fn [i ev] {:i (inc i) :op (name (or (:op ev) :event))
                                           :ts (:ts ev) :label (event-label st ev)})
                               evs))}))
```

Routing changes in `handler` — thread `store-at` into every read route (write routes untouched; a frozen store can never reach them, and even if it did, commit! throws):

```clojure
(= uri "/api/state")   (json-resp (state-payload (store-at st (params "at"))))
(= uri "/api/mold")    (json-resp (mold-payload (store-at st (params "at")) (params "id") (params "view")))
(= uri "/api/events")  (json-resp (events-payload st))
(= uri "/api/links")   (json-resp (nb/links (store-at st (params "at")) (params "space")))
```

For `/api/notebook` only the GET branch changes:

```clojure
(= uri "/api/notebook")(if (= :post (:request-method req))
                         (json-resp (notebook-op! st (body-json req)))
                         (json-resp (notebook-payload (store-at st (params "at")) (params "id"))))
```

And `/api/object/:id` (its params come from the URI; `parse-query` already parsed `query-string` into `params`):

```clojure
(str/starts-with? uri "/api/object/")
(json-resp (mold-payload (store-at st (params "at"))
                         (java.net.URLDecoder/decode (subs uri (count "/api/object/")) "UTF-8") nil))
```

NOTE: `title-of` labels events with CURRENT titles (a hint, not a snapshot) — deliberate, cheap, humane. `/api/events` itself always reads the live log.

- [ ] **Step 4: Run to verify pass** — full suite green (47 tests expected).
- [ ] **Step 5: Commit** — `git commit -am "feat: ?at= time travel on every read + /api/events scrubber labels"`

---

### Task 3: frontend — the ⏱ scrubber

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: API + state.** In the `API` object, thread an optional `at` through the four readers and add `events`:

```js
  state: at => fetch('/api/state'+(at!=null?'?at='+at:'')).then(r=>r.json()),
  object: (id,at) => fetch('/api/object/'+encodeURIComponent(id)+(at!=null?'?at='+at:'')).then(r=>r.json()),
  mold: (id,view,at) => fetch('/api/mold?id='+encodeURIComponent(id)+'&view='+encodeURIComponent(view)+(at!=null?'&at='+at:'')).then(r=>r.json()),
  notebook: (id,at) => fetch('/api/notebook?id='+encodeURIComponent(id)+(at!=null?'&at='+at:'')).then(r=>r.json()),
  events: () => fetch('/api/events').then(r=>r.json()),
```

(Existing call sites pass no `at` → unchanged behavior.) Global near `STATE`: `let TIME=null;   // {n,total,events} while scrubbing, else null` and a helper `const tat=()=>TIME?TIME.n:undefined;` Pass `tat()` from `openObject` (`API.object(id,tat())`), `remold` (`API.mold(id,view,tat())`) and `renderNotebook` (`API.notebook(sp.id,tat())`) so focusing/molding in the past reads the past.

- [ ] **Step 2: topbar button + scrubber strip.** Next to the `ƒ functions` button in the topbar HTML:

```html
  <button class="undoBtn" id="timeBtn" title="scrub the substrate's history — view any past moment, read-only">⏱ time</button>
```

Near the toast div:

```html
<div class="timebar" id="timebar">
  <button class="btn ghost sm" id="timeNow">↩ now</button>
  <input type="range" id="timeRange" min="0" max="0" value="0">
  <div class="timelabel" id="timeLabel">—</div>
</div>
```

CSS (near .toast):

```css
  .timebar{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);z-index:75;display:none;align-items:center;gap:12px;
           background:var(--white);border:1px solid var(--accent-line);border-radius:999px;padding:8px 16px;box-shadow:0 20px 50px -30px rgba(20,30,25,.5);width:min(720px,92vw)}
  .timebar.on{display:flex}
  .timebar input[type=range]{flex:1}
  .timelabel{font-family:var(--mono);font-size:11px;color:var(--muted);white-space:nowrap;max-width:40%;overflow:hidden;text-overflow:ellipsis}
  .world.timemode .actions,.world.timemode .cellbar,.world.timemode .addrow,
  .world.timemode .mkcell,.world.timemode select[data-remold]{display:none!important}
```

NOTE: `select#moldsel` (object detail) stays usable in time mode — remolding is a read. Only `select[data-remold]` (cell set-view, a write) is hidden.

- [ ] **Step 3: the mode.** Place after the layout section:

```js
// ---------------- time scrubber (the record, never the recall) ----------------
const timebar=document.getElementById('timebar'), timeRange=document.getElementById('timeRange'),
      timeLabel=document.getElementById('timeLabel');
let timeDeb;
async function enterTime(){
  const ev=await API.events();
  TIME={n:ev.total,total:ev.total,events:ev.events};
  timeRange.min=0; timeRange.max=ev.total; timeRange.value=ev.total;
  timebar.classList.add('on'); overview(); paintTime();
}
function timeCaption(){
  if(!TIME) return '—';
  if(TIME.n>=TIME.total) return 'now · '+TIME.total+' events';
  const e=TIME.events[TIME.n-1];
  return TIME.n===0 ? 'the beginning — before the first event'
       : 'event '+TIME.n+'/'+TIME.total+' · '+(e?e.label:'')+(e&&e.ts?' · '+new Date(e.ts).toLocaleString():'');
}
function paintTime(){
  timeLabel.textContent=timeCaption();
  world.classList.toggle('timemode', !!TIME && TIME.n<TIME.total);
}
async function scrubTo(n){
  TIME.n=n; paintTime();
  clearTimeout(timeDeb);
  timeDeb=setTimeout(async()=>{
    if(!TIME) return;
    const want=TIME.n;
    const s=await API.state(want<TIME.total?want:undefined);
    if(!TIME||TIME.n!==want) return;                       // stale response
    applyState(s); mode='overview'; openId=null; rebuild();
  },120);
}
async function exitTime(){
  TIME=null; timebar.classList.remove('on'); world.classList.remove('timemode');
  applyState(await API.state()); rebuild();
}
document.getElementById('timeBtn').addEventListener('click',()=>TIME?exitTime():enterTime());
document.getElementById('timeNow').addEventListener('click',exitTime);
timeRange.addEventListener('input',()=>scrubTo(+timeRange.value));
```

- [ ] **Step 4: guards.** (a) In `cellOp` and the `undo` click handler, first line: `if(TIME){ showToast('you\'re viewing the past — ↩ now to edit'); return; }` (b) In the global LEAP keydown handler, before focusing LEAP: `if(TIME) return;` — but let Escape through: in the Escape branch of the global handlers, FIRST check `if(TIME){ exitTime(); return; }`. (c) `safeRebuild`/`pollJob` completions: at the top of `safeRebuild` add `if(TIME){ setTimeout(safeRebuild,4000); return; }` so a finishing background job never yanks the user out of the past.

- [ ] **Step 5: Verify headless.** `node --check` the extracted script. Scratch server on :7779 (`LOCI_DATA=$(mktemp -d)`, never :7777): `GET /api/events` → labels+total; `GET /api/state?at=3` → 3 events' worth of spaces/objects; `GET /api/state?at=0` → empty; `GET /api/notebook?id=space:world&at=999` == live; `GET /api/mold?id=tbl:countries&view=&at=2` works or honest error if the table doesn't exist yet at 2 (verify no 500); served `/` contains `timebar`, `⏱ time`. Kill scratch server. `clojure -M:test` green.

- [ ] **Step 6: Commit** — `git commit -am "feat: ⏱ time scrubber — view any past moment, read-only, esc to now"`

---

### Task 4: docs + final verify

**Files:**
- Modify: `README.md`, `docs/walkthrough.md`

- [ ] **Step 1: README** — endpoints table gains:

```markdown
| `GET /api/events` | the log, humanely labeled — feeds the ⏱ scrubber |
| any read + `?at=N` | the same payload as-of event N — read-only time travel |
```

- [ ] **Step 2: walkthrough** — new flow:

```markdown
## 5 · Scrub time (offline)
Hit **⏱ time**. Drag the scrubber: the whole workspace reconstructs itself at
any past event — before the import, before the deep-dive, before everything.
Focus a notebook to read it as it was; nothing is editable in the past. This
is the same event log undo walks — time made visible. **↩ now** (or esc)
returns to the present. The ⌾ memory pane stays live: the scrubber travels
the record, never the recall.
```

- [ ] **Step 3: full verify** — suite green, node --check, one full curl pass (events → state?at → notebook?at → mold?at), scratch server killed.
- [ ] **Step 4: Commit** — `git commit -am "docs: time scrubber — README endpoint + walkthrough flow 5"`

---

## Out of scope (deferred)
- "Rewind to here" (multi-undo) and redo
- Diff view between two moments
- Scrubbing LEAP/memory (recall is deliberately not time-scrubbed)
- Per-notebook (filtered) timelines
