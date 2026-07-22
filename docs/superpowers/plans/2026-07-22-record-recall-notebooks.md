# Record/Recall Notebooks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend loci into a complete record/recall prototype: persistent deterministic substrate + a Clojure-native AI-memory engine, notebook=space with moldable cells, computed connectedness, and universal LEAP search — per the spec at `docs/superpowers/specs/2026-07-22-record-recall-notebooks-design.md`.

**Architecture:** Two parallel persisted logs — the substrate event log (deterministic, undoable) behind the existing `Store` protocol, and a memory fact log (revisable, decaying, never undone) behind the existing `Recall` protocol. Notebooks are spaces whose `:value` gains ordered `:cells`; links between notebooks are computed, never stored. The agent only proposes (JSON specs, distilled facts, subtopics); the substrate executes.

**Tech Stack:** Clojure 1.12, http-kit, data.json, SCI, clojure.test + cognitect test-runner (new), vanilla JS frontend. No new runtime dependencies.

**Conventions for every task:** Run tests with `clojure -M:test`. Run a single namespace with `clojure -M:test -n loci.memory-test`. The server is `clojure -M:serve` on http://localhost:7777. Commit after every green step with the exact message given. All file paths are repo-relative from `/home/damirk/src/archy`.

---

### Task 1: Test infrastructure

**Files:**
- Modify: `deps.edn`
- Create: `test/loci/smoke_test.clj`

- [ ] **Step 1.1: Add the `:test` alias**

In `deps.edn`, add to the `:aliases` map (after `:demo`):

```clojure
  ;; run the unit tests:  clojure -M:test
  :test  {:extra-paths ["test"]
          :extra-deps  {io.github.cognitect-labs/test-runner
                        {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
          :main-opts   ["-m" "cognitect.test-runner"]}
```

- [ ] **Step 1.2: Write a smoke test**

Create `test/loci/smoke_test.clj`:

```clojure
(ns loci.smoke-test
  "Proves the test runner is wired. Kept as a canary."
  (:require [clojure.test :refer [deftest is]]
            [loci.substrate :as sub]))

(deftest store-round-trip
  (let [s (sub/fresh-store)]
    (sub/commit! s {:op :put :id "x" :value {:id "x" :value 1}})
    (is (= 1 (:value (sub/object s "x"))))
    (sub/undo! s)
    (is (nil? (sub/object s "x")))))
```

- [ ] **Step 1.3: Run tests, verify pass**

Run: `clojure -M:test`
Expected: `1 tests, 2 assertions, 0 failures` (first run downloads the test-runner).

- [ ] **Step 1.4: Commit**

```bash
git add deps.edn test/loci/smoke_test.clj
git commit -m "test: add clojure.test runner alias and smoke test"
```

---

### Task 2: Persistent substrate store

**Files:**
- Modify: `src/loci/substrate.clj`
- Create: `test/loci/substrate_test.clj`

- [ ] **Step 2.1: Write the failing tests**

Create `test/loci/substrate_test.clj`:

```clojure
(ns loci.substrate-test
  (:require [clojure.test :refer [deftest is]]
            [loci.substrate :as sub]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/substrate.edn"))

(deftest persistent-store-replays
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
    (is (= "world" (:value (sub/object s "a"))))
    ;; a brand-new store over the same file replays to identical state
    (let [s2 (sub/persistent-store path)]
      (is (= 2 (count (sub/history s2))))
      (is (= "world" (:value (sub/object s2 "a"))))
      (is (= (sub/state s) (sub/state s2))))))

(deftest persistent-undo-truncates-file
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
    (sub/undo! s)
    (is (nil? (sub/object s "b")))
    (let [s2 (sub/persistent-store path)]
      (is (= 1 (count (sub/history s2))))
      (is (nil? (sub/object s2 "b"))))))
```

- [ ] **Step 2.2: Run to verify failure**

Run: `clojure -M:test -n loci.substrate-test`
Expected: FAIL — `persistent-store` is not defined (compile error).

- [ ] **Step 2.3: Implement `PersistentStore`**

In `src/loci/substrate.clj`: change the `ns` form to add requires (it currently has none):

```clojure
(ns loci.substrate
  "Layer 1: the deterministic substrate — an append-only event log.
   ... (keep the existing docstring unchanged) ..."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))
```

Then append at the end of the file (after `fresh-store`):

```clojure
;; ----------------------------------------------------------------------------
;; durable flavour — same Store protocol, events land on disk as EDN lines.
;; Boot replays the file; undo! rewrites it (logs are small; correctness over
;; cleverness). Reset = delete the data dir.
;; ----------------------------------------------------------------------------

(defn data-dir
  "Where the logs live. Overridable so demos/tests never clobber real data."
  []
  (or (System/getProperty "loci.data-dir") (System/getenv "LOCI_DATA") "data"))

(defn- load-events [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc []]
          (let [ev (edn/read {:eof ::eof} r)]
            (if (= ::eof ev) acc (recur (conj acc ev))))))
      [])))

(defn- write-all! [file events]
  (io/make-parents (io/file file))
  (spit file (apply str (map #(str (pr-str %) "\n") events))))

(defrecord PersistentStore [!log file]
  Store
  (commit! [_ event]
    (let [ev (assoc event :ts (System/currentTimeMillis))]
      (io/make-parents (io/file file))
      (spit file (str (pr-str ev) "\n") :append true)
      (count (swap! !log conj ev))))
  (state   [_] (materialize @!log))
  (objects [this] (:objects (state this)))
  (object  [this id] (get (objects this) id))
  (history [_] @!log)
  (undo!   [_]
    (let [l (swap! !log (fn [l] (cond-> l (seq l) pop)))]
      (write-all! file l)
      (count l)))
  (as-of   [_ n] (materialize (take n @!log))))

(defn persistent-store
  ([] (persistent-store (str (data-dir) "/substrate.edn")))
  ([path] (->PersistentStore (atom (load-events path)) path)))
```

- [ ] **Step 2.4: Run to verify pass**

Run: `clojure -M:test -n loci.substrate-test`
Expected: `2 tests, 6 assertions, 0 failures`.

- [ ] **Step 2.5: Commit**

```bash
git add src/loci/substrate.clj test/loci/substrate_test.clj
git commit -m "feat: PersistentStore — substrate events persist as EDN lines, replay on boot"
```

---

### Task 3: Memory engine (`loci.memory`)

**Files:**
- Create: `src/loci/memory.clj`
- Create: `test/loci/memory_test.clj`

- [ ] **Step 3.1: Write the failing tests**

Create `test/loci/memory_test.clj`:

```clojure
(ns loci.memory-test
  (:require [clojure.test :refer [deftest is]]
            [loci.memory :as mem]
            [loci.mold :as mold]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/memory.edn"))

(deftest remember-and-recall-ranked
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "TSMC produces most leading-edge logic chips."
                   {:entities ["tsmc" "taiwan"] :source {:obj "find:semis-1" :space "space:semis"}})
    (mold/remember m "Serbia's inflation spiked to about 12% in 2022."
                   {:entities ["serbia"] :source {:obj "doc:serbia-econ" :space "space:serbia"}})
    (let [r (mold/recall m "taiwan chips" {:k 5})]
      (is (= 1 (count r)))                                   ; only the relevant fact scores
      (is (= "find:semis-1" (get-in (first r) [:source :obj])))
      (is (pos? (:score (first r)))))))

(deftest near-duplicate-reinforces
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "TSMC produces most leading-edge logic chips." {:entities ["tsmc"]})
    (mold/remember m "TSMC produces most of the leading-edge logic chips." {:entities ["taiwan"]})
    (let [fs (mem/all-facts m)]
      (is (= 1 (count fs)))
      (is (= 2 (:strength (first fs))))
      (is (= #{"tsmc" "taiwan"} (set (:entities (first fs))))))))

(deftest reload-is-last-wins-by-id
  (let [path (tmpfile)
        m    (mem/file-memory path)]
    (mold/remember m "MRR compounds monthly at a steady rate." {:entities ["mrr"]})
    (mold/remember m "MRR compounds monthly at a steady pace." {:entities ["finance"]})
    ;; reinforcement appended a second line with the same :id — reload dedupes
    (let [fs (mem/all-facts (mem/file-memory path))]
      (is (= 1 (count fs)))
      (is (= 2 (:strength (first fs)))))))
```

- [ ] **Step 3.2: Run to verify failure**

Run: `clojure -M:test -n loci.memory-test`
Expected: FAIL — namespace `loci.memory` not found.

- [ ] **Step 3.3: Implement the engine**

Create `src/loci/memory.clj`:

```clojure
(ns loci.memory
  "Layer 3: the recall half of record/recall — a Clojure-native AI-memory
   engine behind the loci.mold/Recall protocol.

   A fact is {:id :fact :entities :source :ts :strength}. remember reinforces
   near-duplicates instead of duplicating; recall fuses keyword, entity,
   recency and strength signals. This is the agent's domain: revisable and
   decaying — deliberately NOT the substrate, and never touched by undo.

   Persistence is an append-only EDN-lines file; reinforcement appends the
   updated fact under the same :id and load is last-wins by :id."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [loci.mold :as mold]
            [loci.substrate :as sub]))

(defn- tokens [s] (set (re-seq #"[a-z0-9][a-z0-9.%-]*" (str/lower-case (str s)))))

(defn- jaccard [a b]
  (if (or (empty? a) (empty? b))
    0.0
    (/ (count (set/intersection a b)) (double (count (set/union a b))))))

(defn- decay
  "1.0 now, halved after ~30 days — recency as a multiplier, not a cutoff."
  [ts now]
  (/ 1.0 (+ 1.0 (/ (max 0 (- now ts)) (* 30.0 86400000.0)))))

(defn- score [qt now f]
  (let [kw  (jaccard qt (tokens (:fact f)))
        es  (set (map str/lower-case (:entities f)))
        ent (if (empty? es) 0.0
                (/ (count (set/intersection qt es)) (double (count es))))]
    (* (+ (* 0.6 kw) (* 0.4 ent))
       (decay (:ts f) now)
       (+ 1.0 (* 0.25 (dec (:strength f 1)))))))

(defn- append-line! [file rec]
  (io/make-parents (io/file file))
  (spit file (str (pr-str rec) "\n") :append true))

(defn- load-facts [file]
  (let [f (io/file file)]
    (if (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (loop [acc {}]
          (let [rec (edn/read {:eof ::eof} r)]
            (if (= ::eof rec) acc (recur (assoc acc (:id rec) rec))))))
      {})))

(defrecord FileMemory [!facts file]
  mold/Recall
  (remember [_ fact opts]
    (let [ft  (tokens fact)
          dup (some (fn [f] (when (>= (jaccard ft (tokens (:fact f))) 0.6) f))
                    (vals @!facts))
          rec (if dup
                (-> dup
                    (assoc :fact fact :ts (System/currentTimeMillis))
                    (update :strength (fnil inc 1))
                    (update :entities #(vec (distinct (concat % (:entities opts))))))
                {:id (str "mem-" (inc (count @!facts)))
                 :fact fact :entities (vec (:entities opts))
                 :source (:source opts) :ts (System/currentTimeMillis) :strength 1})]
      (append-line! file rec)
      (swap! !facts assoc (:id rec) rec)
      :ok))
  (recall [_ query opts]
    (let [qt (tokens query) now (System/currentTimeMillis) k (or (:k opts) 6)]
      (->> (vals @!facts)
           (map #(assoc % :score (score qt now %)))
           (filter #(pos? (:score %)))
           (sort-by :score >)
           (take k)
           vec))))

(defn file-memory
  ([] (file-memory (str (sub/data-dir) "/memory.edn")))
  ([path] (->FileMemory (atom (load-facts path)) path)))

(defn all-facts
  "Every remembered fact, newest first — the browsable memory pane."
  [m]
  (->> (vals @(:!facts m)) (sort-by :ts >) vec))

(defonce ^{:doc "the server's memory singleton (data-dir resolved at first use)"}
  memory (delay (file-memory)))
```

- [ ] **Step 3.4: Run to verify pass**

Run: `clojure -M:test -n loci.memory-test`
Expected: `3 tests, 8 assertions, 0 failures`.

- [ ] **Step 3.5: Commit**

```bash
git add src/loci/memory.clj test/loci/memory_test.clj
git commit -m "feat: Clojure-native memory engine — remember/reinforce/recall behind the Recall protocol"
```

---

### Task 4: Notebook core (`loci.notebook`)

**Files:**
- Create: `src/loci/notebook.clj`
- Create: `test/loci/notebook_test.clj`

- [ ] **Step 4.1: Write the failing tests**

Create `test/loci/notebook_test.clj`:

```clojure
(ns loci.notebook-test
  (:require [clojure.test :refer [deftest is]]
            [loci.notebook :as nb]
            [loci.substrate :as sub]))

(deftest cells-normalization
  ;; legacy :members upgrade to ref cells; :cells pass through
  (is (= [{:ref "a"} {:ref "b"}] (nb/cells-of {:value {:members ["a" "b"]}})))
  (is (= [{:text "hi"} {:ref "a" :view "table/bar"}]
         (nb/cells-of {:value {:cells [{:text "hi"} {:ref "a" :view "table/bar"}]}}))))

(deftest cell-ops
  (let [cells [{:text "a"} {:ref "t1"}]]
    (is (= [{:text "a"} {:ref "t1"} {:text "new"}]
           (nb/cell-op cells {:op "add-text" :text "new"})))
    (is (= [{:text "a"} {:ref "t1"} {:ref "t2" :view "table/bar"}]
           (nb/cell-op cells {:op "add-ref" :ref "t2" :view "table/bar"})))
    (is (= [{:text "edited"} {:ref "t1"}]
           (nb/cell-op cells {:op "edit-text" :idx 0 :text "edited"})))
    (is (= [{:text "a"} {:ref "t1" :view "table/pivot"}]
           (nb/cell-op cells {:op "set-view" :idx 1 :view "table/pivot"})))
    (is (= [{:text "a"} {:ref "t1"}]
           (nb/cell-op (nb/cell-op cells {:op "set-view" :idx 1 :view "table/pivot"})
                       {:op "set-view" :idx 1 :view ""})))   ; empty view clears back to default
    (is (= [{:ref "t1"}] (nb/cell-op cells {:op "remove" :idx 0})))
    (is (= [{:ref "t1"} {:text "a"}] (nb/cell-op cells {:op "move" :idx 0 :to 1})))))

(deftest links-reasons
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "tbl:d" :value {:id "tbl:d" :kind :table :title "D" :value [{:a 2}]
                                                  :from "tbl:x" :via "fn:x-1"}})
    (sub/commit! st {:op :put :id "space:hub" :value {:id "space:hub" :kind :space :title "Hub"
                                                      :value {:cells [{:ref "tbl:x"}]}}})
    (sub/commit! st {:op :put :id "space:sub" :value {:id "space:sub" :kind :space :title "Sub"
                                                      :value {:cells [{:ref "tbl:x"}]
                                                              :spawned-by {:space "space:hub" :prompt "p"}}}})
    (sub/commit! st {:op :put :id "space:der" :value {:id "space:der" :kind :space :title "Der"
                                                      :value {:cells [{:ref "tbl:d"}]}}})
    (let [{:keys [connected also-in]} (nb/links st "space:hub")
          by-id (into {} (map (juxt :id identity) connected))]
      (is (= #{"space:sub" "space:der"} (set (keys by-id))))
      (is (= #{{:type "spawned"} {:type "shares" :obj "tbl:x"}}
             (set (:reasons (by-id "space:sub")))))
      (is (= [{:type "derived" :obj "tbl:x"}] (:reasons (by-id "space:der"))))
      (is (= [{:id "space:sub" :title "Sub"}] (get also-in "tbl:x"))))))
```

- [ ] **Step 4.2: Run to verify failure**

Run: `clojure -M:test -n loci.notebook-test`
Expected: FAIL — namespace `loci.notebook` not found.

- [ ] **Step 4.3: Implement notebook core**

Create `src/loci/notebook.clj`:

```clojure
(ns loci.notebook
  "Notebook = space. A space's :value carries ordered :cells — prose
   ({:text s}) interleaved with object references ({:ref id, :view v?}).
   Data is never copied into a cell; re-molding a cell is changing its :view.

   Legacy {:members [id …]} normalizes to ref cells on read; all writes write
   :cells. Every mutation is ONE substrate event (the full new :cells vector),
   hence undoable.

   Links between notebooks are COMPUTED here, never stored: shared refs,
   spawn edges (:spawned-by), and derivation lineage (:from/:via)."
  (:require [clojure.set :as set]
            [loci.substrate :as sub]))

(defn cells-of [obj]
  (let [v (:value obj)]
    (vec (or (:cells v) (map (fn [id] {:ref id}) (:members v))))))

(defn refs-of [obj] (set (keep :ref (cells-of obj))))

(defn notebooks [st] (->> (sub/objects st) vals (filter #(= :space (:kind %)))))

(defn cell-op
  "Pure: apply one UI cell operation to a cells vector.
   op ∈ add-text | add-ref | edit-text | set-view | remove | move."
  [cells {:keys [op idx text ref view to]}]
  (let [cells (vec cells)]
    (case op
      "add-text"  (conj cells {:text (or text "")})
      "add-ref"   (conj cells (cond-> {:ref ref} (seq view) (assoc :view view)))
      "edit-text" (assoc cells idx {:text (or text "")})
      "set-view"  (update cells idx #(if (seq view) (assoc % :view view) (dissoc % :view)))
      "remove"    (vec (concat (subvec cells 0 idx) (subvec cells (inc idx))))
      "move"      (let [c  (cells idx)
                        w  (vec (concat (subvec cells 0 idx) (subvec cells (inc idx))))
                        to (max 0 (min (count w) to))]
                    (vec (concat (subvec w 0 to) [c] (subvec w to))))
      cells)))

(defn set-cells-event [space-id cells]
  {:op :assoc :id space-id :path [:value :cells] :value (vec cells)})

(defn append-cell-event
  "Event appending one cell to a notebook (normalizes legacy :members)."
  [st space-id cell]
  (set-cells-event space-id (conj (cells-of (sub/object st space-id)) cell)))

(defn links
  "Connectedness for one notebook: {:connected [{:id :title :reasons […]}]
   :also-in {obj-id [{:id :title}]}}. reason :type ∈ shares|spawned|spawned-by|derived."
  [st space-id]
  (let [nb      (sub/object st space-id)
        mine    (refs-of nb)
        objs    (sub/objects st)
        others  (remove #(= (:id %) space-id) (notebooks st))
        srcs    (fn [refs] (set (keep #(:from (objs %)) refs)))
        my-srcs (srcs mine)
        connected
        (vec (keep
              (fn [o]
                (let [theirs  (refs-of o)
                      shares  (set/intersection mine theirs)
                      lineage (set/union
                               (set/difference (set/intersection my-srcs theirs) shares)
                               (set/difference (set/intersection (srcs theirs) mine) shares))
                      reasons (-> []
                                  (cond-> (= (get-in nb [:value :spawned-by :space]) (:id o))
                                    (conj {:type "spawned-by"}))
                                  (cond-> (= (get-in o [:value :spawned-by :space]) space-id)
                                    (conj {:type "spawned"}))
                                  (into (map (fn [r] {:type "shares" :obj r}) (sort shares)))
                                  (into (map (fn [s] {:type "derived" :obj s}) (sort lineage))))]
                  (when (seq reasons) {:id (:id o) :title (:title o) :reasons reasons})))
              others))
        also-in
        (into {} (keep (fn [r]
                         (let [in (vec (keep #(when ((refs-of %) r) {:id (:id %) :title (:title %)})
                                             others))]
                           (when (seq in) [r in])))
                       mine))]
    {:connected connected :also-in also-in}))
```

- [ ] **Step 4.4: Run to verify pass**

Run: `clojure -M:test -n loci.notebook-test`
Expected: `3 tests, 13 assertions, 0 failures`.

- [ ] **Step 4.5: Run the full suite (regression)**

Run: `clojure -M:test`
Expected: all green, `0 failures`.

- [ ] **Step 4.6: Commit**

```bash
git add src/loci/notebook.clj test/loci/notebook_test.clj
git commit -m "feat: notebook core — cells, pure cell ops, computed links"
```

---

### Task 5: Seeds + persistent boot (`loci.content`, `loci.demo` guard)

**Files:**
- Modify: `src/loci/content.clj` (ns requires; `space:world` seed; new `space:semis` seed; `store` delay; `members`)
- Modify: `src/loci/demo.clj` (scratch data dir)

- [ ] **Step 5.1: Point content at the persistent store and notebook helpers**

In `src/loci/content.clj`, add to the `ns` `:require` vector:

```clojure
            [loci.notebook :as nb]
```

Replace the seeded-store section (currently `(defonce store (delay (seed! (sub/fresh-store))))`) with:

```clojure
(defonce store
  (delay (let [s (sub/persistent-store)]
           ;; a non-empty log on disk wins; otherwise seed (which writes the log)
           (if (seq (sub/history s)) s (seed! s)))))
```

Replace the `members` fn with:

```clojure
(defn members [store space-id]
  (->> (nb/cells-of (sub/object store space-id))
       (keep :ref)
       (map #(sub/object store %))))
```

- [ ] **Step 5.2: Re-seed `space:world` as an authored notebook; add the `space:semis` hub**

In `src/loci/content.clj`, in the `objects` def, replace the `space:world` entry:

```clojure
    (obj "space:world" :space "World — countries & economies"
         {:intent "Compare the largest economies by GDP, population and density."
          :cells [{:text "Nominal GDP concentrates hard: the United States and China together produce roughly 45% of the output of the 26 largest economies listed here."}
                  {:ref "tbl:countries" :view "table/bar"}
                  {:text "Grouped by continent the picture shifts — Europe's many mid-size economies stack up against the two giants."}
                  {:ref "tbl:countries" :view "table/pivot"}
                  {:text "The narrative version, for reading end to end:"}
                  {:ref "report:world"}]})
```

And add a new entry directly after the `space:commerce` entry:

```clojure
    (obj "space:semis" :space "Semiconductors — research hub"
         {:intent "Map the semiconductor supply chain: who makes what, and where are the chokepoints."
          :cells [{:text "Research hub. Use Research to gather the map; Deep-dive spawns focused notebooks that stay connected here."}]})
```

- [ ] **Step 5.3: Give the demo a scratch data dir**

In `src/loci/demo.clj`, make the first line of `-main` (before `(let [store @c/store]`):

```clojure
  ;; never touch the real data/ — the demo gets its own throwaway substrate
  (System/setProperty "loci.data-dir"
                      (str (System/getProperty "java.io.tmpdir") "/loci-demo-" (System/currentTimeMillis)))
```

- [ ] **Step 5.4: Verify — demo runs clean, twice, and `data/` persists for the server**

Run: `clojure -M:demo`
Expected: the existing walkthrough output, no exceptions, and NO `data/` directory created in the repo (check with `ls data 2>&1` → "No such file or directory").

Run: `clojure -M:test`
Expected: all green.

Then: `clojure -M:serve &` , wait 3s, `curl -s localhost:7777/api/state | head -c 300`, then `kill %1`.
Expected: JSON with spaces including `space:semis`; a `data/substrate.edn` file now exists (`wc -l data/substrate.edn` ≈ number of seeded objects). Then `rm -rf data` to reset for later tasks.

- [ ] **Step 5.5: Commit**

```bash
git add src/loci/content.clj src/loci/demo.clj
git commit -m "feat: persistent boot, authored world-economy notebook, semiconductor hub seed"
```

---

### Task 6: Agent additions (`distill-facts`, `propose-subtopics`)

**Files:**
- Modify: `src/loci/agent.clj`

These call the DeepSeek API, so no unit tests — they are thin JSON-mode wrappers in the file's existing style; correctness is exercised in Task 8's manual verification.

- [ ] **Step 6.1: Add the two functions**

Append to `src/loci/agent.clj` (after `plan-space`):

```clojure
(defn distill-facts
  "Post-flow memory distillation: pull 1-5 durable, atomic facts out of what an
   agent flow produced. Returns [{:fact string :entities [string]} …]."
  [context text]
  (let [sys (str "Extract 1-5 durable, atomic facts worth remembering from this work — "
                 "figures, causal claims, named entities. One sentence each; skip process "
                 "notes and pleasantries. Respond ONLY as JSON "
                 "{\"facts\":[{\"fact\":\"…\",\"entities\":[\"lowercase\",…]}]}."
                 (when (seq (str context)) (str "\nThe work was about: " context)))]
    (:facts (json/read-str (chat [{:role "system" :content sys}
                                  {:role "user" :content (str text)}] :json? true)
                           :key-fn keyword))))

(defn propose-subtopics
  "Deep-dive planning: given a research-hub notebook, propose 2-3 focused
   subtopics. Returns [{:title string :intent string :query string} …]."
  [title intent digest]
  (let [sys (str "You plan research deep-dives. Given a hub notebook, propose 2-3 focused "
                 "subtopics that each deserve their own notebook. Respond ONLY as JSON "
                 "{\"subtopics\":[{\"title\":\"short\",\"intent\":\"one sentence\","
                 "\"query\":\"the research question to pursue\"}]}.\n"
                 "Hub: " title ". Intent: " intent ".\nFindings so far:\n" digest)]
    (:subtopics (json/read-str (chat [{:role "system" :content sys}
                                      {:role "user" :content "Propose the deep-dives now."}]
                                     :json? true)
                               :key-fn keyword))))
```

- [ ] **Step 6.2: Verify it compiles**

Run: `clojure -M -e "(require 'loci.agent) (println :ok)"`
Expected: `:ok`.

- [ ] **Step 6.3: Commit**

```bash
git add src/loci/agent.clj
git commit -m "feat: agent proposes distilled facts and deep-dive subtopics (JSON mode)"
```

---

### Task 7: Server — memory integration + cells everywhere

**Files:**
- Modify: `src/loci/server.clj`
- Modify: `src/loci/tools.clj`

Every place that appended to `[:value :members]` switches to a cell append; every agent flow gains recall-injection before and distillation after.

- [ ] **Step 7.1: Add requires**

In `src/loci/server.clj` ns `:require`, add:

```clojure
            [loci.memory :as mem]
            [loci.notebook :as nb]
```

In `src/loci/tools.clj` ns `:require`, add:

```clojure
            [loci.notebook :as nb]
```

- [ ] **Step 7.2: `state-payload` derives members from cells**

In `state-payload`, replace the `:members` line of the spaces map:

```clojure
                                 :members (vec (keep :ref (nb/cells-of s)))
```

- [ ] **Step 7.3: Memory helpers**

In `src/loci/server.clj`, insert directly after the `obj-digest` defn:

```clojure
;; ---- the recall seam, used by every agent flow ----
;; remembered-context injects what the agent already learned (cited);
;; distill! writes new memory AFTER a flow — async, best-effort, never undone.
(defn- remembered-context [prompt]
  (let [facts (try (mold/recall @mem/memory prompt {:k 6}) (catch Exception _ nil))]
    (when (seq facts)
      (str "\n\nREMEMBERED (distilled from earlier work — cite as ⌾ id when it shapes your answer):\n"
           (str/join "\n" (map #(str "- " (:fact %) " (⌾ "
                                     (or (get-in % [:source :obj]) (get-in % [:source :space]) "memory") ")")
                               facts))))))

(defn- distill! [prompt text obj-id space]
  (future
    (try
      (doseq [{:keys [fact entities]} (agent/distill-facts prompt text)]
        (mold/remember @mem/memory fact {:entities (mapv str entities)
                                         :source {:obj obj-id :space space}}))
      (catch Exception _))))
```

- [ ] **Step 7.4: `ask!` — inject recall, distill after**

In `ask!`, change the `sys` binding's first segment to append remembered context, and capture the answer:

```clojure
          sys     (str "Answer the user's question about their workspace. Cite the object ids you used. "
                       "Do NOT guess any table figure — call query_table for exact numbers. "
                       "If the data doesn't say, say so plainly. Be concise, markdown.\n\n"
                       "DOCS:\n" (str/join "\n\n" (map obj-digest texty))
                       "\n\nTABLES (query these by id):\n" catalog
                       (remembered-context prompt))
```

and replace the return expression:

```clojure
      (let [answer (agent/chat-tools [{:role "system" :content sys} {:role "user" :content prompt}]
                                     tools/specs tool-fn)]
        (distill! prompt answer nil space)
        {:answer answer})
```

Also in `ask!`, replace the space-scoped objs lookup (`(keep #(sub/object st %) (get-in sp [:value :members]))`) with:

```clojure
                    (keep #(sub/object st (:ref %)) (nb/cells-of sp))
```

- [ ] **Step 7.5: `delegate!` — cells, recall, distill**

Replace `delegate!` wholesale with:

```clojure
(defn delegate! [st space]
  (let [sp (sub/object st space)
        members (->> (nb/cells-of sp) (keep #(sub/object st (:ref %)))
                     (remove #(#{:space :viewspec :applet :fn} (:kind %))))
        texty   (remove #(= :table (:kind %)) members)
        tbls    (filter #(= :table (:kind %)) members)
        allowed (set (map :id tbls))
        catalog (str/join "\n" (map (fn [o] (str "- " (:id o) " — " (:title o)
                                                 " (cols: " (str/join ", " (map name (keys (first (:value o)))))
                                                 "; " (count (:value o)) " rows)")) tbls))
        sys (str "You are a concise analyst. Draft a short markdown brief (≤150 words, with a heading) "
                 "for this workspace space. Use query_table for any exact figure — never guess. Cite object ids.\n\n"
                 "Space: " (:title sp) ". Intent: " (get-in sp [:value :intent]) ".\n\n"
                 "DOCS:\n" (str/join "\n\n" (map obj-digest texty)) "\n\nTABLES (query by id):\n" catalog
                 (remembered-context (str (:title sp) " " (get-in sp [:value :intent]))))
        tool-fn (fn [nm a] (if (and (= nm "query_table") (not (allowed (:table_id a))))
                             {:error "that table is not in this space"}
                             (tools/dispatch st nm a)))
        text (try (agent/chat-tools [{:role "system" :content sys}
                                     {:role "user" :content "Write the brief now."}]
                                    tools/specs tool-fn)
                  (catch Exception e (str "# Draft for " (:title sp) "\n\n_(agent unavailable: " (.getMessage e) ")_")))
        dn (count (filter #(str/starts-with? % "draft:") (nb/refs-of sp)))
        did (str "draft:" (subs space (inc (str/index-of space ":"))) "-" (inc dn))
        draft {:id did :kind :doc :title (str "Draft — " (:title sp)) :value text}]
    (sub/commit! st {:op :tx :events [{:op :put :id did :value draft}
                                      (nb/append-cell-event st space {:ref did})]})
    (distill! (str "brief for " (:title sp)) text did space)
    {:state (state-payload st) :openId did}))
```

- [ ] **Step 7.6: `agent-ctx` — cells**

In `agent-ctx`, replace the member lookup line with:

```clojure
        objs (if-let [sp (and space (sub/object st space))]
               (keep #(sub/object st (:ref %)) (nb/cells-of sp))
               (vals (sub/objects st)))
```

- [ ] **Step 7.7: `research!` — cells, recall, distill**

Replace `research!` wholesale with:

```clojure
(defn research! [st space prompt]
  (try
    (let [{:keys [context tool-fn]} (agent-ctx st space)
          saved (atom [])
          tf (fn [nm a] (let [r (tool-fn nm a)]
                          (when (:saved_as r) (swap! saved conj (:saved_as r)))
                          r))
          sys (str "You are a research assistant for a workspace space. Use web_search for external facts "
                   "and query_table for the user's own data. If your findings are tabular, or you can "
                   "EXTRACT structured rows from a document or technical text in the context below, call "
                   "save_table ONCE with ALL the rows — that table IS the deliverable. After saving it, do "
                   "NOT reproduce the table in your note: write only a 2-3 bullet summary of what the data "
                   "shows and a final '## Sources' section. If there is nothing tabular, write a normal "
                   "markdown findings note instead. Be specific.\n\n" context
                   (remembered-context prompt))
          text (agent/chat-tools [{:role "system" :content sys} {:role "user" :content prompt}]
                                 tools/specs tf)
          tid (first @saved)
          sp (sub/object st space)
          n (count (filter #(str/starts-with? % "find:") (nb/refs-of sp)))
          fid (str "find:" (subs space (inc (str/index-of space ":"))) "-" (inc n))
          p (str/trim prompt)
          title (str "Findings — " (if (> (count p) 44) (str (subs p 0 44) "…") p))]
      (sub/commit! st {:op :tx :events [{:op :put :id fid :value {:id fid :kind :doc :title title :value text}}
                                        (nb/append-cell-event st space {:ref fid})]})
      (distill! prompt text fid space)
      ;; when extraction produced a real table, THAT is the artifact — open it,
      ;; not the prose note. ponytail: open the data, keep the note as context.
      {:state (state-payload st) :openId (or tid fid)})
    (catch Exception e {:error (.getMessage e)})))
```

Note: `save_table` with a space appends its own cell — see step 7.10 — so the extract table also lands in the notebook.

- [ ] **Step 7.8: `keep-note!` — cells**

Replace `keep-note!` wholesale with:

```clojure
(defn keep-note! [st space title text]
  (let [sp  (sub/object st space)
        n   (count (filter #(str/starts-with? % "note:") (nb/refs-of sp)))
        nid (str "note:" (subs space (inc (str/index-of space ":"))) "-" (inc n))]
    (sub/commit! st {:op :tx :events [{:op :put :id nid :value {:id nid :kind :doc :title title :value text}}
                                      (nb/append-cell-event st space {:ref nid})]})
    {:state (state-payload st) :openId nid}))
```

- [ ] **Step 7.9: `compute-clj!` and `import-csv!` — cells**

In `compute-clj!`, replace the space branch of the `evs` binding:

```clojure
                  evs (cond-> [{:op :put :id fid :value fobj}
                               {:op :put :id nid :value tobj}]
                        (and space (sub/object st space))
                        (conj (nb/append-cell-event st space {:ref nid})))
```

In `import-csv!`, replace the `evs` binding:

```clojure
          evs (if (and space (sub/object st space))
                (conj base (nb/append-cell-event st space {:ref nid}))
                base)
```

- [ ] **Step 7.10: `tools/save-table!` — cells**

In `src/loci/tools.clj`, replace the body of `save-table!`'s inner `let` commit section (keep the fn's docstring and empty-rows guard):

```clojure
      (let [nid (str "tbl:extract-" (count (table-objs st)) "-" (inc (rand-int 9999)))
            obj {:id nid :kind :table :title (or title "Extracted table") :value rows}]
        (sub/commit! st (if (and space (sub/object st space))
                          {:op :tx :events [{:op :put :id nid :value obj}
                                            (nb/append-cell-event st space {:ref nid})]}
                          {:op :put :id nid :value obj}))
        {:saved_as nid :rows (count rows) :columns (mapv name (keys (first rows)))})
```

(The `members` local in the old version disappears.)

- [ ] **Step 7.11: Verify compile + full suite + offline server smoke**

Run: `clojure -M -e "(require 'loci.server) (println :ok)"` → `:ok`
Run: `clojure -M:test` → all green.
Run: `clojure -M:serve &`, wait 3s, then:
`curl -s -X POST localhost:7777/api/keep-note -d '{"space":"space:world","title":"t","text":"hello note"}' | head -c 200`
Expected: JSON state; the note landed as a cell (check `curl -s localhost:7777/api/state | grep -o 'note:world-1'`). Then `kill %1 && rm -rf data`.

- [ ] **Step 7.12: Commit**

```bash
git add src/loci/server.clj src/loci/tools.clj
git commit -m "feat: agent flows write cells, inject recall, distill memory after"
```

---

### Task 8: Server — notebook/links/memory/deep-dive endpoints + LEAP everything

**Files:**
- Modify: `src/loci/server.clj`
- Create: `test/loci/server_test.clj`

- [ ] **Step 8.1: Write the failing LEAP test**

Create `test/loci/server_test.clj`:

```clojure
(ns loci.server-test
  (:require [clojure.test :refer [deftest is]]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.server :as srv]
            [loci.substrate :as sub]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/memory.edn"))

(deftest leap-searches-everything
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "doc:x" :value {:id "doc:x" :kind :doc :title "Pricing notes"
                                                  :value "The plan rose 18% in March."}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "Notebook N"
                                                    :value {:intent "i"
                                                            :cells [{:text "churn concentrated in young accounts"}]}}})
    (mold/remember m "Outreach recovers about 22% of downgrades." {:entities ["retention"]})
    (let [groups (fn [q] (set (map :group (srv/leap-payload st m q))))]
      (is (contains? (groups "churn") "prose"))        ; cell prose
      (is (contains? (groups "march") "in text"))      ; doc body, title doesn't match
      (is (contains? (groups "downgrades") "memory"))  ; memory fact
      (is (contains? (groups "notebook") "space")))))  ; notebook by title
```

- [ ] **Step 8.2: Run to verify failure**

Run: `clojure -M:test -n loci.server-test`
Expected: FAIL — `leap-payload` has arity 2, not 3 (or wrong groups).

- [ ] **Step 8.3: Extend `leap-payload`**

In `src/loci/server.clj`, replace `leap-payload` wholesale:

```clojure
(defn leap-payload
  "ONE incremental search across everything: objects, notebook prose, doc
   bodies, memory facts, view verbs. Content groups only appear once there's
   a query; each group is capped so it stays incremental-fast."
  [st mem q]
  (let [q    (str/lower-case (or q ""))
        hit? (fn [& ss] (str/includes? (str/lower-case (str/join " " (map str ss))) q))
        ell  (fn [s n] (let [s (str/replace (str s) "\n" " ")]
                         (if (> (count s) n) (str (subs s 0 n) "…") s)))
        snip (fn [text] (let [i (or (str/index-of (str/lower-case text) q) 0)]
                          (ell (subs text (max 0 (- i 20))) 70)))
        cap  (fn [xs] (vec (take 8 xs)))
        objs  (->> (sub/objects st) vals
                   (remove #(#{:viewspec :applet :fn} (:kind %)))
                   (filter #(or (= q "") (hit? (:title %) (:id %) (name (:kind %)))))
                   (map (fn [o] {:id (:id o) :label (:title o) :group (name (:kind o))})))
        verbs (->> @mold/registry
                   (map (fn [v] {:id (kw->str (:id v)) :label (str "view as " (:label v)) :group "viewer"}))
                   (filter #(or (= q "") (hit? (:label %) (:id %)))))
        prose (when (seq q)
                (for [o (nb/notebooks st), c (nb/cells-of o)
                      :when (and (:text c) (hit? (:text c)))]
                  {:id (:id o) :label (str (:title o) " · " (snip (:text c))) :group "prose"}))
        intext (when (seq q)
                 (for [o (vals (sub/objects st))
                       :when (and (= :doc (:kind o)) (string? (:value o))
                                  (hit? (:value o)) (not (hit? (:title o) (:id o))))]
                   {:id (:id o) :label (str (:title o) " · …" (snip (:value o))) :group "in text"}))
        mems (when (seq q)
               (map (fn [f] {:id "__memory__" :label (:fact f) :group "memory"})
                    (mold/recall mem q {:k 8})))]
    (vec (concat (cap objs) (cap prose) (cap intext) (cap mems) verbs))))
```

And update the handler's leap route to pass the memory singleton:

```clojure
      (= uri "/api/leap")    (json-resp (leap-payload st @mem/memory (params "q")))
```

- [ ] **Step 8.4: Run to verify pass**

Run: `clojure -M:test -n loci.server-test`
Expected: `1 tests, 4 assertions, 0 failures`.

- [ ] **Step 8.5: Notebook payload + op, links, memory, deep-dive**

In `src/loci/server.clj`, insert after `mold-payload`:

```clojure
;; ---- notebook payload: cells hydrated (each ref molded by its chosen view),
;; rail links and also-in chips computed fresh every time ----
(defn notebook-payload [st id]
  (let [o (sub/object st id)
        {:keys [connected also-in]} (nb/links st id)
        cells (vec (map-indexed
                    (fn [i c]
                      (cond
                        (:text c) {:i i :type "text" :text (:text c)}
                        (sub/object st (:ref c))
                        (merge {:i i :type "ref"
                                :also-in (get also-in (:ref c))
                                :from (:from (sub/object st (:ref c)))
                                :via  (:via (sub/object st (:ref c)))}
                               (mold-payload st (:ref c) (:view c)))
                        :else {:i i :type "missing" :ref (:ref c)}))
                    (nb/cells-of o)))]
    {:id id :title (:title o) :intent (get-in o [:value :intent])
     :spawned-by (get-in o [:value :spawned-by])
     :cells cells :connected connected :events (count (sub/history st))}))

(defn notebook-op! [st {:keys [space] :as body}]
  (if-not (sub/object st space)
    {:error (str "no notebook " space)}
    (do (sub/commit! st (nb/set-cells-event
                         space (nb/cell-op (nb/cells-of (sub/object st space)) body)))
        {:state (state-payload st) :notebook (notebook-payload st space)})))
```

Insert after `research!` (it calls `research!`, so order matters):

```clojure
;; deep-dive: the agent proposes subtopics (grounded in the hub's findings AND
;; recalled memory), each spawned as a connected notebook and researched.
(defn deep-dive! [st space]
  (try
    (let [sp     (sub/object st space)
          digest (str (->> (nb/cells-of sp) (keep #(sub/object st (:ref %)))
                           (map obj-digest) (str/join "\n"))
                      (remembered-context (str (:title sp) " " (get-in sp [:value :intent]))))
          subs*  (take 3 (agent/propose-subtopics (:title sp) (get-in sp [:value :intent]) digest))
          spawned
          (vec (for [{:keys [title intent query]} subs*]
                 (let [n   (count (nb/notebooks st))
                       sid (str "space:dd-" (inc n))]
                   (sub/commit! st {:op :put :id sid
                                    :value {:id sid :kind :space :title title
                                            :value {:intent intent :cells []
                                                    :spawned-by {:space space :prompt query}}}})
                   (research! st sid query)
                   sid)))]
      {:state (state-payload st) :spawned spawned})
    (catch Exception e {:error (.getMessage e)})))
```

- [ ] **Step 8.6: Routes**

In the handler `cond`, add before the `/api/object/` clause:

```clojure
      (= uri "/api/notebook")(if (= :post (:request-method req))
                               (json-resp (notebook-op! st (body-json req)))
                               (json-resp (notebook-payload st (params "id"))))
      (= uri "/api/links")   (json-resp (nb/links st (params "space")))
      (= uri "/api/memory")  (json-resp {:facts (let [qq (params "q")]
                                                  (if (seq qq)
                                                    (mold/recall @mem/memory qq {:k 20})
                                                    (mem/all-facts @mem/memory)))})
      (= uri "/api/deep-dive")(json-resp (deep-dive! st (:space (body-json req))))
```

- [ ] **Step 8.7: Verify — suite + offline endpoint smoke**

Run: `clojure -M:test` → all green.
Run: `clojure -M:serve &`, wait 3s:

```bash
curl -s 'localhost:7777/api/notebook?id=space:world' | python3 -m json.tool | head -30
curl -s -X POST localhost:7777/api/notebook -d '{"space":"space:world","op":"add-text","text":"scratch cell"}' | grep -o 'scratch cell'
curl -s 'localhost:7777/api/links?space=space:retention' | python3 -m json.tool
curl -s 'localhost:7777/api/memory' | head -c 120
curl -s 'localhost:7777/api/leap?q=churn' | python3 -m json.tool | head -20
```

Expected: notebook JSON with hydrated `cells` (first is text, second a molded bar chart); the add-text echo; links showing `space:support` sharing `tbl:themes`; `{"facts":[]}`; leap results including a `prose` group hit. Then `kill %1 && rm -rf data`.

- [ ] **Step 8.8: Commit**

```bash
git add src/loci/server.clj test/loci/server_test.clj
git commit -m "feat: notebook/links/memory/deep-dive endpoints; LEAP searches prose, docs and memory"
```

---

### Task 9: Frontend — notebook cells, rail, chips

**Files:**
- Modify: `resources/public/index.html`

No unit tests — verification is the running shell. Keep the existing style exactly; all additions use the established CSS variables.

- [ ] **Step 9.1: CSS additions**

In the `<style>` block, append before the `@media` rule:

```css
  .prose{font-size:13.5px;line-height:1.65;color:var(--ink-2);margin:0 0 4px;white-space:pre-wrap}
  .cellwrap{position:relative;padding:2px 0;border-radius:8px}
  .cellwrap:hover{background:rgba(20,30,25,.02)}
  .cellbar{position:absolute;top:2px;right:0;display:none;gap:4px;z-index:2}
  .cellwrap:hover .cellbar{display:flex}
  .cellbar button{border:1px solid var(--line);background:var(--white);border-radius:6px;font-size:10px;font-family:var(--mono);color:var(--muted);cursor:pointer;padding:2px 6px}
  .cellbar button:hover{color:var(--ink);border-color:var(--record)}
  .cellmold{border:1px solid var(--line);border-radius:10px;background:var(--white);margin:6px 0 10px;overflow:hidden}
  .cellmold .hd{display:flex;align-items:center;gap:8px;padding:7px 11px;background:var(--panel);border-bottom:1px solid var(--line-2);font-size:12.5px}
  .cellmold .hd .t{font-weight:500;cursor:pointer} .cellmold .hd .t:hover{color:var(--accent)}
  .cellmold .hd select{margin-left:auto;padding:2px 6px;border-radius:6px;border:1px solid var(--line);font:inherit;font-size:11.5px}
  .cellmold .bd{padding:10px 12px;overflow-x:auto}
  .chips{font-size:10.5px;font-family:var(--mono);color:var(--faint);padding:5px 11px;border-top:1px dashed var(--line-2)}
  .ghostchip{display:inline-block;padding:0 6px;border-radius:4px;background:var(--panel);border:1px solid var(--line);color:var(--muted);cursor:pointer;margin:0 2px}
  .ghostchip:hover{border-color:var(--accent-line);color:var(--accent)}
  .nbrow{display:flex;gap:16px;align-items:flex-start}
  .rail{flex:none;width:210px}
  .raillink{border:1px solid var(--line);border-radius:9px;background:var(--white);padding:8px 11px;margin-bottom:8px;cursor:pointer;font-size:12.5px}
  .raillink:hover{border-color:var(--accent-line)}
  .raillink .why{font-family:var(--mono);font-size:10px;color:var(--faint);line-height:1.5}
  .addrow{display:flex;gap:8px;margin-top:8px;align-items:center}
  .addrow input{border:1px solid var(--line);border-radius:8px;padding:6px 9px;font:inherit;font-size:12.5px}
  .memfact{border:1px solid var(--line);border-radius:9px;padding:9px 12px;margin-bottom:9px}
  .memfact .src{font-family:var(--mono);font-size:10.5px;color:var(--muted);margin-top:4px}
  .memfact .dots{color:var(--accent);letter-spacing:2px}
```

- [ ] **Step 9.2: API additions**

In the `API` object, add:

```js
  notebook: id => fetch('/api/notebook?id='+encodeURIComponent(id)).then(r=>r.json()),
  notebookOp: body => POST('/api/notebook', body),
  memory: q => fetch('/api/memory'+(q?'?q='+encodeURIComponent(q):'')).then(r=>r.json()),
  deepDive: space => POST('/api/deep-dive',{space}),
```

- [ ] **Step 9.3: Split `renderBody` — lightweight overview card vs full notebook**

Replace the existing `renderBody` function wholesale with:

```js
function renderBody(i){
  const sp=STATE.spaces[i], b=document.getElementById('body'+i);
  if(i===focusIdx && openId){ renderDetail(i); return; }
  if(mode==='focus' && i===focusIdx){ renderNotebook(i); return; }
  // overview card: cheap title list only — the full notebook loads on focus
  let h='<div class="sec-h">in this notebook</div>';
  if(!sp.members.length) h+='<div style="color:var(--muted);font-size:13px;padding:4px 0 8px">Empty — Research or Import to fill it.</div>';
  sp.members.forEach(id=>{ const o=STATE.objects[id]||{id,title:id,kind:'?'};
    h+='<div class="obj" data-open="'+id+'"><div class="ttl">'+esc(o.title)+' <span class="chip">'+id+'</span></div></div>'; });
  b.innerHTML=h;
  b.querySelectorAll('[data-open]').forEach(el=>el.addEventListener('click',()=>openObject(el.dataset.open)));
}

async function cellOp(i,body){
  const sp=STATE.spaces[i];
  const res=await API.notebookOp(Object.assign({space:sp.id},body));
  if(res.error){ showToast(res.error); return; }
  applyState(res.state); renderBody(i);
  showToast('Notebook updated — reversible', undo);
}

function editProse(i,idx,text){
  const b=document.getElementById('body'+i);
  const target = idx===null ? b.querySelector('.addrow') : b.querySelector('[data-cell="'+idx+'"]');
  if(!target) return;
  const ed=document.createElement('div');
  ed.innerHTML='<textarea class="prosed" style="width:100%;min-height:90px;font:13px/1.6 var(--sans);border:1px solid var(--accent-line);border-radius:8px;padding:9px;box-sizing:border-box">'+esc(text||'')+'</textarea>'+
    '<div class="actions" style="margin:6px 0 10px"><button class="btn sm" data-a="save">Save</button><button class="btn ghost sm" data-a="cancel">Cancel</button></div>';
  target.replaceWith(ed);
  ed.querySelector('[data-a="save"]').addEventListener('click',()=>{
    const t=ed.querySelector('.prosed').value;
    cellOp(i, idx===null?{op:'add-text',text:t}:{op:'edit-text',idx,text:t});
  });
  ed.querySelector('[data-a="cancel"]').addEventListener('click',()=>renderBody(i));
  ed.querySelector('.prosed').focus();
}

async function renderNotebook(i){
  const sp=STATE.spaces[i], b=document.getElementById('body'+i);
  b.innerHTML='<div class="sec-h">loading notebook…</div>';
  const nb=await API.notebook(sp.id);
  if(nb.error){ b.innerHTML='<div style="color:var(--attn);font-size:13px">'+esc(nb.error)+'</div>'; return; }
  let cellsH='';
  nb.cells.forEach(c=>{
    const bar='<div class="cellbar">'+(c.i>0?'<button data-act="up" data-i="'+c.i+'">↑</button>':'')+
      (c.i<nb.cells.length-1?'<button data-act="down" data-i="'+c.i+'">↓</button>':'')+
      (c.type==='text'?'<button data-act="edit" data-i="'+c.i+'">✎</button>':'')+
      '<button data-act="rm" data-i="'+c.i+'">✕</button></div>';
    if(c.type==='text'){
      cellsH+='<div class="cellwrap" data-cell="'+c.i+'">'+bar+'<p class="prose">'+esc(c.text)+'</p></div>';
    } else if(c.type==='missing'){
      cellsH+='<div class="cellwrap" data-cell="'+c.i+'">'+bar+'<div style="color:var(--attn);font-size:12px">missing object '+esc(c.ref)+'</div></div>';
    } else {
      let opts=''; (c.alternatives||[]).forEach(([vid,label])=>{ opts+='<option value="'+vid+'"'+(vid===c.view?' selected':'')+'>'+esc(label)+'</option>'; });
      let chips='';
      if(c.from) chips+='↳ derived from <span class="ghostchip" data-open="'+esc(c.from)+'">'+esc(c.from)+'</span>'+(c.via?' via '+esc(c.via):'');
      if(c['also-in']&&c['also-in'].length) chips+=(chips?' · ':'')+'↳ also in '+
        c['also-in'].map(s=>'<span class="ghostchip" data-space="'+esc(s.id)+'">'+esc(s.title)+'</span>').join(' ');
      cellsH+='<div class="cellwrap" data-cell="'+c.i+'">'+bar+
        '<div class="cellmold"><div class="hd"><span class="t" data-open="'+esc(c.id)+'">'+esc(c.title)+'</span>'+
        '<span class="chip">'+esc(c.id)+'</span><select data-remold="'+c.i+'">'+opts+'</select></div>'+
        '<div class="bd">'+renderMold(c)+'</div>'+(chips?'<div class="chips">'+chips+'</div>':'')+'</div></div>';
    }
  });
  const dl='<datalist id="objlist">'+Object.values(STATE.objects).map(o=>'<option value="'+esc(o.id)+'">'+esc(o.title)+'</option>').join('')+'</datalist>';
  const add='<div class="addrow"><button class="btn ghost sm" id="addProse">＋ prose</button>'+
    '<input id="addRef" list="objlist" placeholder="＋ object by id…">'+dl+'</div>';
  const rail = nb.connected.length
    ? '<div class="rail"><div class="sec-h">connected · '+nb.connected.length+'</div>'+
      nb.connected.map(cn=>'<div class="raillink" data-space="'+esc(cn.id)+'"><b>'+esc(cn.title)+'</b><br><span class="why">'+
        cn.reasons.map(r=>r.type==='spawned'?'deep-dive · spawned from here':r.type==='spawned-by'?'spawned this notebook':r.type==='shares'?'shares '+esc(r.obj):'lineage · '+esc(r.obj)).join('<br>')+
        '</span></div>').join('')+'</div>'
    : '';
  const actions='<div class="actions" style="margin-bottom:6px">'+
    '<button class="btn sm" id="askBtn">✦ Ask</button>'+
    '<button class="btn ghost sm" id="researchBtn">🔍 Research</button>'+
    '<button class="btn ghost sm" id="ddBtn">⌘ Deep-dive</button>'+
    '<button class="btn ghost sm" id="draftBtn">✎ Draft</button>'+
    '<button class="btn ghost sm" id="importBtn">＋ Import CSV</button></div>'+
    (nb['spawned-by']?'<div style="font-size:11px;font-family:var(--mono);color:var(--faint);margin-bottom:8px">spawned from <span class="ghostchip" data-space="'+esc(nb['spawned-by'].space)+'">'+esc(nb['spawned-by'].space)+'</span> · “'+esc(nb['spawned-by'].prompt||'')+'”</div>':'');
  b.innerHTML=actions+'<div class="nbrow"><div style="flex:1;min-width:0">'+cellsH+add+'</div>'+rail+'</div>';
  b.querySelector('#askBtn').addEventListener('click',()=>startAsk(i));
  b.querySelector('#researchBtn').addEventListener('click',()=>startResearch(i));
  b.querySelector('#draftBtn').addEventListener('click',async()=>{
    const res=await API.delegate(sp.id);
    applyState(res.state); openId=res.openId; rebuild(); openObject(res.openId);
    showToast('Draft written by the agent (reversible)', undo);
  });
  b.querySelector('#importBtn').addEventListener('click',()=>startImport(i));
  b.querySelector('#ddBtn').addEventListener('click',async()=>{
    b.innerHTML='<div class="sec-h">deep-diving</div><div style="color:var(--muted);font-size:13px">the agent is proposing subtopics, spawning connected notebooks and researching each — this takes a while…</div>';
    const res=await API.deepDive(sp.id);
    if(res.error){ showToast('deep-dive: '+res.error); renderBody(i); return; }
    applyState(res.state); rebuild();
    showToast('Spawned '+res.spawned.length+' connected deep-dive notebook'+(res.spawned.length===1?'':'s'));
  });
  b.querySelectorAll('[data-remold]').forEach(sel=>sel.addEventListener('change',()=>cellOp(i,{op:'set-view',idx:+sel.dataset.remold,view:sel.value})));
  b.querySelectorAll('[data-open]').forEach(el=>el.addEventListener('click',()=>openObject(el.dataset.open)));
  b.querySelectorAll('[data-space]').forEach(el=>el.addEventListener('click',()=>{
    const j=STATE.spaces.findIndex(s=>s.id===el.dataset.space); if(j>=0) enter(j);
  }));
  b.querySelectorAll('.cellbar button').forEach(btn=>btn.addEventListener('click',ev=>{
    ev.stopPropagation();
    const idx=+btn.dataset.i, act=btn.dataset.act;
    if(act==='rm') cellOp(i,{op:'remove',idx});
    else if(act==='up') cellOp(i,{op:'move',idx,to:idx-1});
    else if(act==='down') cellOp(i,{op:'move',idx,to:idx+1});
    else if(act==='edit') editProse(i,idx,(nb.cells[idx]||{}).text||'');
  }));
  b.querySelector('#addProse').addEventListener('click',()=>editProse(i,null,''));
  const ar=b.querySelector('#addRef');
  ar.addEventListener('change',()=>{ const id=ar.value.trim(); if(STATE.objects[id]) cellOp(i,{op:'add-ref',ref:id}); });
  // agent-written applet cells run scoped to their own cell
  nb.cells.forEach(c=>{
    if(c.type==='ref'&&c.kind==='applet'){
      const w=b.querySelector('[data-cell="'+c.i+'"]'), host=w&&w.querySelector('#appletHost');
      if(host){ try{(new Function('data','el',c.code))(c.rendered,host);}catch(e){host.textContent='applet error: '+e.message;} }
    }
  });
}
```

- [ ] **Step 9.4: Wording — spaces are notebooks now**

In `buildPanel`, change `'space'` in the status div to `'notebook'` and the eyebrow `'space · intention'` to `'notebook · intention'`. In `renderStrip`, change `' spaces · '` to `' notebooks · '`. In `layout()`, change `'all spaces'` to `'all notebooks'`.

- [ ] **Step 9.5: Verify in the browser**

Run `clojure -M:serve`, open http://localhost:7777. Check, in this order:

1. Focus **World — countries & economies**: prose + bar-molded countries table + pivot + report render as cells.
2. Change the bar cell's mold select to "Rows" → cell re-renders as a table; toast offers Undo; press it → back to bar.
3. Click a prose cell's ✎, edit, Save → text updates; ↺ undo reverts.
4. ＋ prose adds a cell; ＋ object with `tbl:planets` adds a molded cell; ✕ removes; ↑/↓ reorder.
5. Focus **Retention, Q3** — rail shows "connected · 1: Support operations · shares tbl:themes"; clicking it jumps. The `tbl:themes` cell shows an "also in" chip.
6. Overview (esc) still shows all panels as cheap cards.
7. Restart the server — everything you did is still there (persistence). Then `rm -rf data` to reset.

- [ ] **Step 9.6: Commit**

```bash
git add resources/public/index.html
git commit -m "feat: notebook shell — moldable cells, threads rail, also-in/lineage chips"
```

---

### Task 10: Frontend — memory pane + LEAP groups

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 10.1: Memory button in the topbar**

Next to the `fnBtn` button in the topbar HTML, add before it:

```html
  <button class="undoBtn" id="memBtn" title="what the agent remembers — distilled facts with provenance">⌾ memory</button>
```

- [ ] **Step 10.2: The memory pane**

Add after `openFunctions` in the script:

```js
// the agent's memory — browsable, searchable, cited. Revisable and decaying;
// undo never touches it (that's the record/recall split, visible).
async function openMemory(qstr){
  const res=await API.memory(qstr||'');
  const facts=res.facts||[];
  let h='<div class="modalcard" style="max-width:720px"><div class="modal-h">'+
    '<span style="font-family:var(--mono);font-size:11px;color:var(--accent);border:1px solid var(--accent-line);border-radius:6px;padding:2px 7px">MEMORY</span>'+
    '<span class="q">'+facts.length+' remembered fact'+(facts.length===1?'':'s')+(qstr?' for “<b>'+esc(qstr)+'</b>”':'')+'</span></div>'+
    '<div class="modal-b"><input id="memq" placeholder="search memory…" value="'+esc(qstr||'')+'" style="width:100%;box-sizing:border-box;border:1px solid var(--line);border-radius:8px;padding:8px 10px;font:inherit;font-size:13px;margin-bottom:12px">';
  if(!facts.length) h+='<div style="color:var(--muted);font-size:13px">Nothing yet — memory fills as the agent researches, drafts and answers.</div>';
  facts.forEach(f=>{
    const age=Math.round((Date.now()-f.ts)/86400000);
    h+='<div class="memfact"><div style="font-size:13px">'+esc(f.fact)+'</div>'+
      '<div class="src"><span class="dots">'+'●'.repeat(Math.min(5,f.strength||1))+'</span> · '+
      (f.source&&f.source.obj?'<span class="ghostchip" data-open="'+esc(f.source.obj)+'">⌾ '+esc(f.source.obj)+'</span>'
                             :'⌾ '+esc((f.source&&f.source.space)||'workspace'))+
      ' · '+(age<1?'today':age+'d ago')+(f.score!==undefined?' · relevance '+(+f.score).toFixed(2):'')+'</div></div>';
  });
  h+='</div><div class="modal-f"><button class="btn ghost sm" id="closeModal">Close</button>'+
    '<span style="font-size:11.5px;color:var(--muted)">the agent’s recall — revisable, never undone</span></div></div>';
  modal.innerHTML=h; modal.classList.add('on');
  modal.querySelector('#closeModal').addEventListener('click',closeModal);
  const mq=modal.querySelector('#memq');
  mq.addEventListener('keydown',e=>{ if(e.key==='Enter') openMemory(mq.value.trim()); });
  modal.querySelectorAll('[data-open]').forEach(el=>el.addEventListener('click',()=>{ closeModal(); openObject(el.dataset.open); }));
}
document.getElementById('memBtn').addEventListener('click',()=>openMemory(''));
```

- [ ] **Step 10.3: LEAP group names, icons, actions**

In `renderLeap`, replace the group-name line:

```js
  const gname={ask:'Ask',space:'Notebooks',viewer:'View verbs',create:'Create',prose:'In notebooks','in text':'In documents',memory:'Memory'};
  filtered.forEach(e=>{ const grp=gname[e.group]||'Content';
```

and the icon line:

```js
    const ic=e.group==='ask'?'✦':e.group==='space'?'▢':e.group==='viewer'?'▸':e.group==='create'?'✦':e.group==='memory'?'⌾':e.group==='prose'?'¶':e.group==='in text'?'§':'▤';
```

In `act()`, add two branches before the final `else`:

```js
  else if(e.group==='memory'){ const qq=q.value.trim(); closeLeap(); openMemory(qq); }
  else if(e.group==='prose'){ const i=STATE.spaces.findIndex(s=>s.id===e.id); if(i>=0) enter(i); }
```

- [ ] **Step 10.4: Verify in the browser**

Server up, then:

1. Type `churn` in LEAP → groups include "In notebooks" (retention prose), "In documents" (doc bodies), plus tables/notebooks. Enter on a prose hit jumps to its notebook.
2. `⌾ memory` opens the (empty) pane; the search field round-trips.
3. If the DeepSeek key is live: run Ask on a notebook ("which continent has the most GDP in tbl:countries?") → answer arrives; reopen `⌾ memory` after ~5s → distilled facts appear with provenance; LEAP for a word from a fact shows a Memory group.

- [ ] **Step 10.5: Commit**

```bash
git add resources/public/index.html
git commit -m "feat: memory pane and LEAP groups — one search across notebooks, docs, prose, memory"
```

---

### Task 11: Demo extension, walkthrough, final verification

**Files:**
- Modify: `src/loci/demo.clj`
- Create: `docs/walkthrough.md`
- Modify: `README.md`

- [ ] **Step 11.1: Extend the headless demo**

In `src/loci/demo.clj`, add to the ns requires:

```clojure
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
```

(`mold` is already required — keep the existing alias line; add only `mem` and `nb`.)

Then insert before the final `(banner "done")` section:

```clojure
    (banner "notebook = space — ordered cells, molded per cell")
    (doseq [c (nb/cells-of (sub/object store "space:world"))]
      (println (if (:text c)
                 (str "   ¶ " (let [t (:text c)] (if (> (count t) 52) (str (subs t 0 52) "…") t)))
                 (str "   ▤ " (:ref c) (when (:view c) (str "  · molded as " (:view c)))))))

    (banner "persistence — the substrate survives restart")
    (let [n     (count (sub/history store))
          again (sub/persistent-store (str (sub/data-dir) "/substrate.edn"))]
      (println n "events on disk;" (count (sub/history again))
               "replayed into a fresh store — identical state:" (= (sub/state store) (sub/state again))))

    (banner "links — connectedness is computed, never stored")
    (println "space:retention ↔"
             (mapv (fn [c] [(:id c) (mapv :type (:reasons c))])
                   (:connected (nb/links store "space:retention"))))

    (banner "two logs: substrate records, memory recalls")
    (let [m (mem/file-memory (str (sub/data-dir) "/memory.edn"))]
      (mold/remember m "Churn concentrates in accounts under 12 months."
                     {:entities ["churn"] :source {:obj "tbl:revenue"}})
      (mold/remember m "Churn concentrates in accounts under twelve months."
                     {:entities ["retention"] :source {:obj "doc:churn"}})
      (println "2 remembers, near-duplicate →" (count (mem/all-facts m))
               "fact, strength" (:strength (first (mem/all-facts m))))
      (sub/commit! store {:op :assoc :id "doc:churn" :path [:value] :value "scratch"})
      (sub/undo! store)
      (println "after a substrate undo!, memory still holds" (count (mem/all-facts m))
               "fact — undo reverts the record, never the recall")
      (println "recall \"churn\" →" (mapv :fact (mold/recall m "churn" {}))))
```

Note: the old "recall seam (stubbed)" section using `mold/naive-recall` can stay — it documents the seam; the new section shows the real engine.

- [ ] **Step 11.2: Run the demo**

Run: `clojure -M:demo`
Expected additions: the world cells listed (¶/▤), `identical state: true`, `space:retention ↔ [["space:support" ["shares"]]]`, `1 fact, strength 2`, memory count unchanged after undo. Exit 0.

- [ ] **Step 11.3: Write the walkthrough**

Create `docs/walkthrough.md`:

```markdown
# loci — demo walkthrough (the four flows)

Start: `clojure -M:serve` → http://localhost:7777. Reset anytime with `rm -rf data`.
Flows 2–3 need the DeepSeek key; web search needs the Tavily key.

## 1 · A moldable notebook-briefing (offline)
Focus **World — countries & economies**. It's an authored notebook: prose
interleaved with the SAME `tbl:countries` molded two ways (bar, pivot) plus the
full report. Re-mold any cell from its menu — the data is stored once, the cell
only points. Edit prose in place, add cells, reorder — every step is one
substrate event: ↺ undo walks it all back. Restart the server: it's all still there.

## 2 · Research (agent + web)
Focus **Semiconductors — research hub** → **Research** → e.g.
"who fabricates leading-edge chips, and where are the supply chokepoints?".
The agent searches, extracts a table (that table IS the deliverable, molded like
any other), and lands a findings note — both as new cells. Then open **⌾ memory**:
the distilled facts are there, each citing the object it came from.

## 3 · Connected deep-dives (agent)
On the hub, hit **⌘ Deep-dive**. The agent proposes 2-3 subtopics (informed by
what it now REMEMBERS from flow 2 — watch for ⌾ citations), spawns a connected
notebook per subtopic and researches each. The hub grows a **connected** rail;
shared tables show **also in** chips; derived tables show their lineage.
Links are computed from the substrate — nothing to maintain, nothing to go stale.

## 4 · One search across everything (offline)
Tap any key → LEAP. Type `churn`: notebooks, tables, docs, notebook prose,
document bodies and memory facts, in one incremental list. Enter jumps —
a prose hit opens its notebook, a memory hit opens the memory pane.
One gesture; the whole substrate answers.
```

- [ ] **Step 11.4: Update the README**

In `README.md`: in the architecture table, change layer 3's row from stubbed wording to:

```markdown
| 3. Recall / AI-memory | `loci.memory` — Clojure-native engine (keyword+entity+recency+strength), persisted, agent-written | **recall** |
```

Replace the sentence "The **recall layer** is stubbed behind the `loci.mold/Recall` protocol …" with:

```markdown
The **recall layer** is a Clojure-native engine (`loci.memory`) behind the
`loci.mold/Recall` protocol — facts distilled by the agent after every flow,
with provenance, reinforcement and decay. Undo never touches it: undo reverts
the record, not the recall. (A Khora sidecar can still replace it — same seam.)
```

In the endpoint table add rows:

```markdown
| `GET/POST /api/notebook` | a notebook's hydrated cells / one cell operation |
| `GET /api/links?space=` | computed connectedness (shares / spawned / lineage) |
| `GET /api/memory?q=` | the agent's memory — browsable, recall-ranked |
| `POST /api/deep-dive` | spawn + research connected sub-notebooks |
```

In the layout block add:

```
src/loci/memory.clj      layer 3: AI-memory engine (Recall protocol, persisted)
src/loci/notebook.clj    notebook = space: cells, cell ops, computed links
docs/walkthrough.md      the four demo flows, step by step
```

And under Run add: `clojure -M:test` — run the unit tests. Note that state
persists in `data/` (delete it to reset).

- [ ] **Step 11.5: Final full verification**

```bash
clojure -M:test          # all green
clojure -M:demo          # full walkthrough, exit 0, no data/ dir created
rm -rf data && clojure -M:serve &   # boot fresh
sleep 3
curl -s 'localhost:7777/api/leap?q=churn' | grep -o '"group":"prose"' | head -1
curl -s 'localhost:7777/api/notebook?id=space:world' | grep -o '"type":"text"' | head -1
kill %1
```

Expected: tests green; demo clean; both greps hit. Manually run the browser walkthrough in `docs/walkthrough.md` (flows 2–3 only if keys are live).

- [ ] **Step 11.6: Commit**

```bash
git add src/loci/demo.clj docs/walkthrough.md README.md
git commit -m "docs: demo walkthrough, README record/recall update; demo proves both logs"
```

---

## Spec coverage map (self-check)

| Spec section | Tasks |
|---|---|
| §1 notebook = space, cells, ops, back-compat | 4, 5, 7, 9 |
| §2 memory engine, distill, injection, citation | 3, 6, 7, 10 |
| §3 persistence, data-dir, reset | 2, 5 |
| §4 links computed, rail + chips | 4, 8, 9 |
| §5 flow 1 (world notebook) | 5, 9 |
| §5 flow 2 (semis research) | 5, 7, walkthrough |
| §5 flow 3 (deep-dives) | 6, 8, 9, walkthrough |
| §5 flow 4 (LEAP everything) | 8, 10 |
| §6 endpoints + agent fns | 6, 7, 8 |
| §7 frontend | 9, 10 |
| §8 housekeeping (.gitignore) | done pre-plan |
| §9 verification | every task + 11 |
```
