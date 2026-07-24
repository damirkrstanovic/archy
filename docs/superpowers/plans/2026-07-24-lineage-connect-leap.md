# Live Lineage + Connect Spaces + LEAP-Everything Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three thesis-completing features: (A) **live lineage** — recompute a derived table (and everything downstream) from its source in one reversible event; (B) **connect spaces** — the old prototype's non-destructive merge, ported: a NEW space that unions two notebooks, originals intact, reached through LEAP; (C) **LEAP-everything** — applets, view-specs and functions become findable; a hit opens the target object molded by that view.

**Architecture:** (A) `fn-apply!` starts stamping `:params` on derived tables; a pure-ish `rerun!` walks the `:from` DAG in topological order, recomputing each node against its parent's NEW rows (working-map fold), and commits all `:assoc [:value]` updates as ONE `:tx`. (B) `connect!` builds the union space (`:merged-from [a b]`, ref-cells deduped, prose kept) as one `:put`; `nb/links` gains merged reasons both directions; the overview clusters a connected space after its first parent. (C) `leap-payload` gains a "views & functions" group (query-only, like prose) carrying `:target`; the shell opens target-and-remolds.

**Tech Stack:** Clojure server + vanilla-JS shell, cognitect test-runner. Baseline: 47 tests / 157 assertions green on `742ad5a`.

**Decisions:** rerun on a *derived* table refreshes it AND its descendants; rerun on a *source* table refreshes descendants only. Derived tables created before `:params` existed are SKIPPED with an honest reason (never guessed). Connect semantics per the 2026-06-13 prototype decision: new space, union, non-destructive, LEAP-reached. LEAP "views & functions" appears only with a query. All new UI actions respect time mode (`if(TIME)` guard pattern already in the file).

---

### Task 1: live lineage — server (`:params` stamp, `rerun!`, `POST /api/rerun`)

**Files:**
- Modify: `src/loci/server.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests** (append; `trows`/`store-with-table` helpers exist from the palette tests)

```clojure
(deftest fn-apply-stamps-params
  (let [st (store-with-table)
        r  (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "space:n")]
    (is (= {:by "revenue" :n "1" :order "desc"} (:params (sub/object st (:openId r)))))))

(deftest rerun-recomputes-the-chain-in-one-tx
  (let [st (store-with-table)
        ;; b = top-1 of t (lib, params stamped) ; c = doubled b (agent fn, code stored)
        b  (:openId (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} nil))
        _  (sub/commit! st {:op :put :id "fn:t-1"
                            :value {:id "fn:t-1" :kind :fn :title "fn: double"
                                    :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
        c  (:openId (srv/fn-apply! st "tbl:t" "fn:t-1" {} nil))   ; c derives from t too
        ;; now change the SOURCE: EMEA revenue jumps past APAC
        _  (sub/commit! st {:op :assoc :id "tbl:t" :path [:value]
                            :value [{:region "EMEA" :revenue 900} {:region "APAC" :revenue 250}]})
        before (count (sub/history st))
        r  (srv/rerun! st "tbl:t")]
    (is (= (inc before) (count (sub/history st))))                 ; ONE :tx for the whole chain
    (is (= #{b c} (set (:refreshed r))))
    (is (= [{:region "EMEA" :revenue 900}] (:value (sub/object st b))))       ; top-1 follows the new data
    (is (= [1800 500] (map :revenue (:value (sub/object st c)))))             ; doubled fresh rows
    (sub/undo! st)                                                 ; one undo restores BOTH
    (is (= [{:region "APAC" :revenue 250}] (:value (sub/object st b))))))

(deftest rerun-on-a-derived-table-refreshes-it-then-descendants
  (let [st (store-with-table)
        b  (:openId (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "2" :order "desc"} nil))
        _  (sub/commit! st {:op :put :id "fn:t-1"
                            :value {:id "fn:t-1" :kind :fn :title "fn: double"
                                    :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
        c  (:openId (srv/fn-apply! st b "fn:t-1" {} nil))          ; c derives from B (a chain!)
        _  (sub/commit! st {:op :assoc :id "tbl:t" :path [:value]
                            :value [{:region "X" :revenue 7} {:region "Y" :revenue 3}]})
        r  (srv/rerun! st b)]                                      ; hit ↻ on the DERIVED table
    (is (= [b c] (:refreshed r)))                                  ; parent before child
    (is (= [14 6] (map :revenue (:value (sub/object st c)))))))    ; child used b's NEW rows

(deftest rerun-skips-paramless-legacy-and-reports-why
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "tbl:old"
                     :value {:id "tbl:old" :kind :table :title "Old derived"
                             :value [{:a 1}] :from "tbl:t" :via "lib:top"}})   ; no :params
    (let [before (count (sub/history st))
          r (srv/rerun! st "tbl:t")]
      (is (= [] (:refreshed r)))
      (is (= "tbl:old" (:id (first (:skipped r)))))
      (is (string? (:why (first (:skipped r)))))
      (is (= before (count (sub/history st)))))))                  ; nothing to refresh → no event

(deftest rerun-validates
  (let [st (store-with-table)]
    (is (:error (srv/rerun! st "nope")))
    (is (:error (srv/rerun! st "space:n")))))
```

- [ ] **Step 2: Run to verify failure** — `clojure -M:test` → first failure: `fn-apply-stamps-params` assertion (nil params), then `No such var: srv/rerun!`. (Two red reasons — fine; both new behavior.)

- [ ] **Step 3: Implementation.** In `fn-apply!`, stamp params on the derived table (find `tobj`):

```clojure
              tobj {:id nid :kind :table :title (str (:title src) " · " lbl)
                    :value (:rows r) :from id :via fid :params params}
```

Then, after `fn-apply!`:

```clojure
;; ---- live lineage: derived tables know :from/:via/:params, so the whole
;; downstream chain can be recomputed against fresh source rows — one :tx,
;; one undo. Legacy tables without :params are skipped, never guessed. ----
(defn- recompute [st working obj]
  ;; working = {id -> newly-computed rows} for already-refreshed ancestors
  (let [src-id (:from obj)
        rows   (or (get working src-id) (:value (sub/object st src-id)))
        via    (:via obj)]
    (cond
      (nil? rows) {:why (str "source " src-id " is gone")}
      (str/starts-with? (str via) "fn:")
      (if-let [code (get-in (sub/object st via) [:value :code])]
        (try {:rows (run-clj-rows code rows)}
             (catch Exception e {:why (.getMessage e)}))
        {:why (str "function " via " is gone")})
      (str/starts-with? (str via) "lib:")
      (if-let [ps (:params obj)]
        (let [r (fnlib/run-fn via rows ps)]
          (if (:error r) {:why (:error r)} {:rows (json-safe (:rows r))}))
        {:why "made before lineage recorded its parameters — re-apply ƒ to refresh"})
      :else {:why (str "unknown lineage via " via)})))

(defn rerun!
  "Refresh id (if derived) and everything derived from it, transitively.
   Children recompute against their parent's NEW rows. All updates land as
   one :tx. Returns {:state :refreshed [ids] :skipped [{:id :why}]}."
  [st id]
  (let [o (sub/object st id)]
    (if-not (and o (= :table (:kind o)))
      {:error (str "not a table: " id)}
      (let [objs     (vals (sub/objects st))
            children (fn [pid] (->> objs (filter #(= pid (:from %))) (sort-by :id) (map :id)))
            ;; breadth-first over the :from DAG, parents before children
            order    (loop [q (vec (if (:from o) [id] (children id))) seen #{} out []]
                       (if (empty? q)
                         out
                         (let [[x & xs] q]
                           (if (seen x)
                             (recur (vec xs) seen out)
                             (recur (vec (concat xs (children x))) (conj seen x) (conj out x))))))
            {:keys [working refreshed skipped]}
            (reduce (fn [{:keys [working refreshed skipped]} cid]
                      (let [r (recompute st working (sub/object st cid))]
                        (if (:rows r)
                          {:working (assoc working cid (:rows r))
                           :refreshed (conj refreshed cid) :skipped skipped}
                          {:working working :refreshed refreshed
                           :skipped (conj skipped {:id cid :why (:why r)})})))
                    {:working {} :refreshed [] :skipped []}
                    order)]
        (when (seq refreshed)
          (sub/commit! st {:op :tx :events (mapv (fn [cid] {:op :assoc :id cid :path [:value]
                                                            :value (get working cid)})
                                                 refreshed)}))
        {:state (state-payload st) :refreshed refreshed :skipped skipped}))))
```

Route (next to `/api/fn-apply`):

```clojure
(= uri "/api/rerun")   (let [{:keys [id]} (body-json req)] (json-resp (rerun! st id)))
```

NOTE: if a `skipped` ancestor breaks the chain, its descendants recompute against the STALE stored value of that ancestor (working-map miss falls back to the store) — acceptable and honest: they're listed refreshed, the skip is reported. `run-clj-rows`, `json-safe`, `fnlib` already exist.

- [ ] **Step 4: Verify green** — full suite (52 tests expected). **Step 5: Commit** — `feat: live lineage — rerun! recomputes the :from chain, one tx`

---

### Task 2: connect spaces — server (`connect!`, `:merged-from` in links + state)

**Files:**
- Modify: `src/loci/server.clj`, `src/loci/notebook.clj`
- Test: `test/loci/server_test.clj`, `test/loci/notebook_test.clj`

- [ ] **Step 1: Failing tests.** In server_test.clj:

```clojure
(deftest connect-unions-two-notebooks-non-destructively
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:a"
                     :value {:id "space:a" :kind :space :title "Alpha"
                             :value {:intent "ia" :cells [{:text "pa"} {:ref "tbl:x"}]}}})
    (sub/commit! st {:op :put :id "space:b"
                     :value {:id "space:b" :kind :space :title "Beta"
                             :value {:intent "ib" :cells [{:ref "tbl:x" :view "table/pivot"} {:text "pb"}]}}})
    (let [before (count (sub/history st))
          r   (srv/connect! st "space:a" "space:b")
          sid (:openId r)
          sp  (sub/object st sid)]
      (is (= (inc before) (count (sub/history st))))               ; ONE event
      (is (= "Alpha × Beta" (:title sp)))
      (is (= ["space:a" "space:b"] (get-in sp [:value :merged-from])))
      ;; union: prose from both kept, shared ref deduped (first occurrence wins)
      (is (= [{:text "pa"} {:ref "tbl:x"} {:text "pb"}] (get-in sp [:value :cells])))
      ;; originals untouched
      (is (= 2 (count (get-in (sub/object st "space:a") [:value :cells]))))
      ;; state payload exposes merged-from
      (let [by-id (into {} (map (juxt :id identity)) (:spaces (:state r)))]
        (is (= ["space:a" "space:b"] (get-in by-id [sid :merged-from]))))
      ;; undo removes the connected space, originals intact
      (sub/undo! st)
      (is (nil? (sub/object st sid)))
      (is (sub/object st "space:a")))))

(deftest connect-validates
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:a" :value {:id "space:a" :kind :space :title "A" :value {:cells []}}})
    (is (:error (srv/connect! st "space:a" "space:a")))            ; self
    (is (:error (srv/connect! st "space:a" "nope")))               ; missing
    (is (:error (srv/connect! st "nope" "space:a")))
    (is (= 1 (count (sub/history st))))))
```

In notebook_test.clj (match its existing style; it requires `[loci.notebook :as nb]` and `[loci.substrate :as sub]`):

```clojure
(deftest links-surface-merged-both-directions
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:a" :value {:id "space:a" :kind :space :title "A" :value {:cells []}}})
    (sub/commit! st {:op :put :id "space:b" :value {:id "space:b" :kind :space :title "B" :value {:cells []}}})
    (sub/commit! st {:op :put :id "space:mix-1"
                     :value {:id "space:mix-1" :kind :space :title "A × B"
                             :value {:cells [] :merged-from ["space:a" "space:b"]}}})
    (let [reasons (fn [from to] (->> (:connected (nb/links st from))
                                     (filter #(= to (:id %))) first :reasons (map :type) set))]
      (is (contains? (reasons "space:mix-1" "space:a") "merged-from"))   ; child → parent
      (is (contains? (reasons "space:a" "space:mix-1") "merged")))))     ; parent → child
```

- [ ] **Step 2: red** — `No such var: srv/connect!` (and the links test fails with empty reasons once connect! exists but links doesn't — run after each step).

- [ ] **Step 3: Implementation.** In notebook.clj `links`, extend the `reasons` threading (after the two spawned cond->s):

```clojure
                                  (cond-> (some #{(:id o)} (get-in nb [:value :merged-from]))
                                    (conj {:type "merged-from"}))
                                  (cond-> (some #{space-id} (get-in o [:value :merged-from]))
                                    (conj {:type "merged"}))
```

In server.clj, `state-payload`'s space map gains (mirroring the `:spawned-by` cond->):

```clojure
                                  (get-in s [:value :merged-from])
                                  (assoc :merged-from (get-in s [:value :merged-from]))
```

New `connect!` (near `new-space!`):

```clojure
;; ---- connect: the old prototype's non-destructive merge — a NEW space
;; unioning two notebooks (prose kept, shared refs deduped), originals
;; intact, one reversible event. Cross-space work = connect, not move. ----
(defn connect! [st a b]
  (let [oa (sub/object st a) ob (sub/object st b)]
    (cond
      (= a b) {:error "connect two different notebooks"}
      (not (and (= :space (:kind oa)) (= :space (:kind ob))))
      {:error "connect works on two notebooks"}
      :else
      (let [taken (fn [cells] (set (keep :ref cells)))
            ca    (nb/cells-of oa)
            cb    (vec (remove #(and (:ref %) ((taken ca) (:ref %))) (nb/cells-of ob)))
            sid   (next-id st "space:mix-")
            sp    {:id sid :kind :space :title (str (:title oa) " × " (:title ob))
                   :value {:intent (str "everything from “" (:title oa) "” and “" (:title ob) "”, together")
                           :cells (vec (concat ca cb))
                           :merged-from [a b]}}]
        (sub/commit! st {:op :put :id sid :value sp})
        {:state (state-payload st) :openId sid}))))
```

Route:

```clojure
(= uri "/api/connect")(let [{:keys [a b]} (body-json req)] (json-resp (connect! st a b)))
```

- [ ] **Step 4: green** (55 tests expected: 2 in server_test + 1 in notebook_test). **Step 5: Commit** — `feat: connect! — non-destructive space merge, merged links both ways`

---

### Task 3: LEAP-everything — server (views & functions in leap-payload)

**Files:**
- Modify: `src/loci/server.clj`
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Failing test:**

```clojure
(deftest leap-finds-views-and-functions-with-target
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "tbl:p" :value {:id "tbl:p" :kind :table :title "Planets" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "app:p-1"
                     :value {:id "app:p-1" :kind :applet :title "app: orbits"
                             :value {:target "tbl:p" :code ";" :label "▶ orbit animation"}}})
    (sub/commit! st {:op :put :id "fn:p-1"
                     :value {:id "fn:p-1" :kind :fn :title "fn: densities"
                             :value {:source "tbl:p" :lang "clojure" :code "rows"}}})
    (let [hits  (srv/leap-payload st m "orbit")
          orbit (first (filter #(= "app:p-1" (:id %)) hits))]
      (is (some? orbit))
      (is (= "views & functions" (:group orbit)))
      (is (= "tbl:p" (:target orbit))))
    (is (some #(= "fn:p-1" (:id %)) (srv/leap-payload st m "densities")))
    ;; query-only, like prose: the empty listing stays uncluttered
    (is (not-any? #(= "app:p-1" (:id %)) (srv/leap-payload st m "")))))
```

- [ ] **Step 2: red** — assertion failure (applet filtered out by the exclusion set).

- [ ] **Step 3: Implementation.** In `leap-payload`, add a `made` group (pattern-match the existing `prose`/`intext` blocks; place after `intext`):

```clojure
        made  (when (seq q)
                (for [o (vals (sub/objects st))
                      :when (and (#{:applet :viewspec :fn} (:kind o))
                                 (hit? (:title o) (:id o) (get-in o [:value :label])))]
                  {:id (:id o)
                   :label (str (or (get-in o [:value :label]) (:title o))
                               " · on " (or (get-in o [:value :target]) (get-in o [:value :source]) "?"))
                   :group "views & functions"
                   :target (or (get-in o [:value :target]) (get-in o [:value :source]))}))
```

and include `(cap made)` in the final concat (keep existing order; `made` after `intext`, before `mems`).

- [ ] **Step 4: green** (56 tests). **Step 5: Commit** — `feat: LEAP finds views, applets and functions — nothing you made is lost`

---

### Task 4: frontend — ↻ lineage, ⧉ connect via LEAP, views-&-functions hits

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: API methods:**

```js
  rerun: id => POST('/api/rerun',{id}),
  connect: (a,b) => POST('/api/connect',{a,b}),
```

- [ ] **Step 2: ↻ on derived cells.** In `renderNotebook`, the chips string for a cell with `c.from` currently reads `'↳ derived from <chip> via …'` — append right after it:

```js
      if(c.from) chips+=' <button class="mkcell" data-rerun="'+esc(c.id)+'" title="↻ re-run lineage — recompute this table from its source (and everything derived from it)">↻</button>';
```

Wire next to the other cell handlers:

```js
  b.querySelectorAll('[data-rerun]').forEach(el=>el.addEventListener('click',async ev=>{
    ev.stopPropagation();
    if(TIME){ showToast('time mode — ↩ now to edit'); return; }
    let r; try{ r=await API.rerun(el.dataset.rerun); }catch(e){ showToast('rerun: network error'); return; }
    if(r.error){ showToast(r.error); return; }
    if(TIME){ showToast('finished — ↩ now to see it'); return; }
    applyState(r.state); renderBody(i);
    const skips=(r.skipped||[]).length;
    showToast('Recomputed '+r.refreshed.length+' table'+(r.refreshed.length===1?'':'s')+
      (skips?' · '+skips+' skipped':'')+' — reversible', undo);
    if(r.skipped&&r.skipped.length) console.log('rerun skipped:', r.skipped);
  }));
```

- [ ] **Step 3: ⧉ connect via LEAP.** In `renderLeap`, after the existing "Create/Ask" verb injection: when `mode==='focus'` and not TIME, for each space hit `s` in the results with `s.id !== STATE.spaces[focusIdx].id` (cap 2), append to the verbs:

```js
  if(mode==='focus'&&!TIME&&qv){
    const cur=STATE.spaces[focusIdx];
    filtered.filter(r=>r.group==='space'&&r.id!==cur.id).slice(0,2).forEach(s=>{
      filtered.push({id:'__connect:'+s.id, label:'⧉ Connect “'+cur.title+'” + “'+(s.label||s.id)+'”',
                     group:'Create', connect:s.id});
    });
  }
```

(Adapt mechanically to how `filtered` is actually built/ordered — the verb must land in the same list the keyboard navigates.) In `act()` (the Enter handler), before the existing branches:

```js
  if(it.connect){
    let r; try{ r=await API.connect(STATE.spaces[focusIdx].id, it.connect); }catch(e){ showToast('connect: network error'); return; }
    if(r.error){ showToast(r.error); return; }
    if(TIME){ showToast('finished — ↩ now to see it'); return; }
    applyState(r.state); closeLeap(); rebuild();
    const j=STATE.spaces.findIndex(s=>s.id===r.openId); if(j>=0) enter(j);
    showToast('Connected — a new notebook, originals untouched (reversible)', undo);
    return;
  }
```

- [ ] **Step 4: views-&-functions hits.** In `act()`, also before the default open branch:

```js
  if(it.target){                       // a view/applet/function from LEAP
    closeLeap();
    if(it.id.slice(0,3)==='fn:'){ openFunctions(); return; }
    await openObject(it.target);
    await remold(it.target, it.id);
    return;
  }
```

- [ ] **Step 5: merged-from in the shell.** (a) `applyState` clustering: parent lookup becomes `const h=sp['spawned-by']||((sp['merged-from']||[])[0]);` — use that same expression in BOTH places `spawned-by` is read for ordering (byHub build and the root test). (b) `buildPanel`: `const hub=...` same fallback; when the space has `merged-from`, eyebrow `'connected · '+esc(titles joined ' × ')` (look up both parents' titles in STATE.spaces, fall back to ids) and status text `'connected'`. (c) rail reason labels in `renderNotebook`: extend the reason mapper with `r.type==='merged-from'?'connected · made from this notebook':r.type==='merged'?'part of this connection':...` (keep existing cases).

- [ ] **Step 6: verify headless.** `node --check` extracted script. Scratch server :7779 (temp LOCI_DATA, kill after): `POST /api/rerun` on a fresh fn-apply chain (create via curl: fn-apply lib:top on tbl:countries into space:world → assoc… actually simpler: rerun the fn-applied table → expect refreshed:[]/skipped or refreshed with same rows — verify 200 + shape); `POST /api/connect {"a":"space:world","b":"space:cosmos"}` → openId space:mix-1, state shows merged-from, `/api/links?space=space:mix-1` shows both parents with merged-from reasons; `/api/leap?q=orbit` shape unchanged for content, and after creating an applet via substrate curl is overkill — instead verify `/api/leap?q=<some fn title>` once a ƒ apply created… skip creation: unit tests cover leap; just verify `/api/leap?q=x` returns 200 and served `/` contains `data-rerun`, `__connect`, `views & functions` handling code. `clojure -M:test` green.

- [ ] **Step 7: Commit** — `feat: ↻ lineage, ⧉ connect via LEAP, views & functions findable`

---

### Task 5: docs + final verify

**Files:** `README.md`, `docs/walkthrough.md`

- [ ] **Step 1: README** endpoints table gains:

```markdown
| `POST /api/rerun` | recompute a derived table + everything downstream — one reversible event |
| `POST /api/connect` | a NEW notebook unioning two others — non-destructive, originals intact |
```

- [ ] **Step 2: walkthrough.** In flow 1, after the ƒ palette paragraph:

```markdown
Derived tables stay alive: change the source, hit **↻** on the derived cell —
the whole downstream chain recomputes in one reversible step.
```

New flow at the end:

```markdown
## 6 · Connect two notebooks (offline)
Focus a notebook, LEAP, type another notebook's name → **⧉ Connect**. A NEW
notebook appears holding everything from both — the originals untouched, the
connection visible in the overview and both rails. LEAP also finds everything
you've *made*: type a view or function's name and land on its table, molded.
```

- [ ] **Step 3: full verify** — suite green, node --check, the Task 4 curl pass rerun once. **Step 4: Commit** — `docs: lineage rerun, connect, LEAP-everything`

---

## Out of scope (deferred)
- Auto-rerun on source change (watchers) — explicit ↻ only in v1
- Connect >2 spaces at once; disconnect (undo covers it)
- Staleness indicators on derived cells
- LEAP hits for memory-editing or archive/dissolve verbs
