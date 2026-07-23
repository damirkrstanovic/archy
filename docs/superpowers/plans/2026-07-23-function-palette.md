# Function Palette Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A library of built-in single-table transforms (simple-Excel verbs) applicable from any table cell, with a live before/after preview against the real rows, committed as one reversible event.

**Architecture:** New UI-free `loci.fnlib` namespace holds the built-ins as data (`{:id :label :doc :params :pred :run}`), mirroring the viewer-registry pattern (predicate-over-value decides compatibility; incompatible functions are shown greyed with a reason). Three new endpoints: `GET /api/fns?id=` (the palette for a table — built-ins + agent-written `:fn` objects), `POST /api/fn-preview` (runs the transform, returns first-8 rows before/after, commits nothing), `POST /api/fn-apply` (commits derived table + cell append as ONE `:tx`, provenance `:from`/`:via`). Frontend: a **ƒ** button on table cells opens the palette inline under the cell — chips → param controls → preview → apply.

**Tech Stack:** Clojure (server), SCI for agent-written fns (existing path), vanilla JS shell, cognitect test-runner.

**Decisions (from user):** single-table only (no join in v1); palette lives on the cell ("close to data"); incompatible functions greyed with a reason, not hidden. Agent-written functions (created via ✦ make…) appear in the same palette.

---

### Task 1: fnlib core — column helpers + filter

**Files:**
- Create: `src/loci/fnlib.clj`
- Create: `test/loci/fnlib_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(ns loci.fnlib-test
  (:require [clojure.test :refer [deftest is]]
            [loci.fnlib :as fnl]))

(def rows [{:region "EMEA" :revenue 100 :units 5}
           {:region "APAC" :revenue 250 :units 2}
           {:region "EMEA" :revenue 50  :units 9}])

(deftest column-helpers
  (is (= [:revenue :units] (fnl/numeric-cols rows)))
  (is (= [:region] (fnl/cat-cols rows))))

(deftest filter-numeric-and-string
  (is (= [{:region "APAC" :revenue 250 :units 2}]
         (:rows (fnl/run-fn "lib:filter" rows {:col "revenue" :op ">" :value "150"}))))
  (is (= 2 (count (:rows (fnl/run-fn "lib:filter" rows {:col "region" :op "=" :value "EMEA"})))))
  (is (= 2 (count (:rows (fnl/run-fn "lib:filter" rows {:col "region" :op "contains" :value "em"})))))
  ;; zero matches is an honest error, not an empty table in the substrate
  (is (:error (fnl/run-fn "lib:filter" rows {:col "region" :op "=" :value "MARS"}))))

(deftest unknown-function-errors
  (is (:error (fnl/run-fn "lib:nope" rows {}))))
```

- [ ] **Step 2: Run to verify failure**

Run: `clojure -M:test` — Expected: syntax error / No such var: `fnl/numeric-cols` (namespace missing).

- [ ] **Step 3: Minimal implementation**

```clojure
(ns loci.fnlib
  "Built-in single-table transforms — simple-Excel verbs, close to the data.
   A function is DATA {:id :label :doc :params :pred :run}; the registry is a
   vector. UI-free: catalog and run-fn work over plain rows, any shell renders."
  (:refer-clojure :exclude [])
  (:require [clojure.string :as str]))

(defn numeric-cols [rows]
  (vec (for [[k v] (first rows) :when (number? v)] k)))

(defn cat-cols [rows]
  (vec (for [k (keys (first rows))
             :let [vs (map k rows)]
             :when (and (every? string? vs) (<= 2 (count (distinct vs)) 12))]
         k)))

(defn- ->num [s] (try (Double/parseDouble (str s)) (catch Exception _ nil)))

(defn- filter-run [rows {:keys [col op value]}]
  (let [k (keyword col) vn (->num value)]
    (vec (filter (fn [r]
                   (let [v (get r k)]
                     (case op
                       "="  (if (and (number? v) vn) (== v vn) (= (str v) (str value)))
                       "≠"  (if (and (number? v) vn) (not (== v vn)) (not= (str v) (str value)))
                       ">"  (boolean (and (number? v) vn (> v vn)))
                       "<"  (boolean (and (number? v) vn (< v vn)))
                       "contains" (str/includes? (str/lower-case (str v)) (str/lower-case (str value)))
                       false)))
                 rows))))

(def builtins
  [{:id "lib:filter" :label "Filter rows" :doc "keep rows where a column passes a test"
    :params [{:name "col" :type "col"}
             {:name "op" :type "choice" :options ["=" "≠" ">" "<" "contains"]}
             {:name "value" :type "text"}]
    :pred (fn [_] nil) :run filter-run}])

(defn run-fn [fid rows params]
  (if-let [f (first (filter #(= fid (:id %)) builtins))]
    (try
      (let [out ((:run f) rows params)]
        (if (and (seq out) (every? map? out))
          {:rows out}
          {:error "no rows came out — nothing to keep"}))
      (catch Exception e {:error (.getMessage e)}))
    {:error (str "unknown function: " fid)}))
```

- [ ] **Step 4: Run to verify pass** — `clojure -M:test` → 0 failures.

- [ ] **Step 5: Commit** — `git add src/loci/fnlib.clj test/loci/fnlib_test.clj && git commit -m "feat: fnlib — built-in transform registry, filter verb"`

---

### Task 2: group/aggregate + top-N

**Files:**
- Modify: `src/loci/fnlib.clj`
- Test: `test/loci/fnlib_test.clj`

- [ ] **Step 1: Write the failing tests** (append to `fnlib_test.clj`; reuses `rows` from Task 1)

```clojure
(deftest group-aggregates
  (let [out (:rows (fnl/run-fn "lib:group" rows {:by "region" :measure "revenue" :agg "sum"}))]
    (is (= 2 (count out)))
    (is (= 150 (:sum_revenue (first (filter #(= "EMEA" (:region %)) out))))))
  (let [out (:rows (fnl/run-fn "lib:group" rows {:by "region" :measure "revenue" :agg "count"}))]
    (is (= 2 (:count_revenue (first (filter #(= "EMEA" (:region %)) out)))))))

(deftest top-n-sorts-and-takes
  (is (= [250 100] (map :revenue (:rows (fnl/run-fn "lib:top" rows {:by "revenue" :n "2" :order "desc"})))))
  (is (= [50] (map :revenue (:rows (fnl/run-fn "lib:top" rows {:by "revenue" :n "1" :order "asc"}))))))
```

- [ ] **Step 2: Run to verify failure** — `clojure -M:test` → unknown function: lib:group / lib:top.

- [ ] **Step 3: Implementation** (add before `builtins`, extend the vector)

```clojure
(defn- agg-val [agg xs]
  (case agg
    "sum" (reduce + 0 xs)
    "avg" (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) 0)
    "min" (apply min xs)
    "max" (apply max xs)
    "count" (count xs)))

(defn- group-run [rows {:keys [by measure agg]}]
  (let [bk (keyword by) mk (keyword measure) agg (or agg "sum")
        ok (keyword (str agg "_" measure))]
    (vec (for [[g rs] (group-by bk rows)]
           {bk g ok (if (= agg "count") (count rs) (agg-val agg (keep mk rs)))}))))

(defn- top-run [rows {:keys [by n order]}]
  (let [k (keyword by) n (int (or (->num n) 10))
        s (sort-by #(or (get % k) 0) rows)]
    (vec (take n (if (= order "asc") s (reverse s))))))

(defn- needs-numeric [rows] (when (empty? (numeric-cols rows)) "needs a numeric column"))
```

Registry entries (append to `builtins`):

```clojure
   {:id "lib:group" :label "Group & aggregate" :doc "one row per group — sum/avg/min/max/count a column"
    :params [{:name "by" :type "col"} {:name "measure" :type "numcol"}
             {:name "agg" :type "choice" :options ["sum" "avg" "min" "max" "count"]}]
    :pred needs-numeric :run group-run}
   {:id "lib:top" :label "Top N" :doc "sort by a numeric column and keep the first N"
    :params [{:name "by" :type "numcol"} {:name "n" :type "num"}
             {:name "order" :type "choice" :options ["desc" "asc"]}]
    :pred needs-numeric :run top-run}
```

- [ ] **Step 4: Run to verify pass** — `clojure -M:test` → 0 failures.
- [ ] **Step 5: Commit** — `git commit -am "feat: fnlib group/aggregate + top-N"`

---

### Task 3: pivot + change-vs-previous + share-of-total

**Files:**
- Modify: `src/loci/fnlib.clj`
- Test: `test/loci/fnlib_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(def rows2 [{:region "EMEA" :channel "web"    :revenue 100}
            {:region "EMEA" :channel "retail" :revenue 40}
            {:region "APAC" :channel "web"    :revenue 250}])

(deftest pivot-goes-wide
  (let [out (:rows (fnl/run-fn "lib:pivot" rows2 {:rows_col "region" :cols_col "channel"
                                                  :measure "revenue" :agg "sum"}))
        emea (first (filter #(= "EMEA" (:region %)) out))]
    (is (= 2 (count out)))
    (is (= 100 (:web emea)))
    (is (= 40 (:retail emea)))))

(deftest delta-adds-change-columns
  (let [ts [{:month "Jan" :mrr 100} {:month "Feb" :mrr 110}]
        out (:rows (fnl/run-fn "lib:delta" ts {:col "mrr"}))]
    (is (nil? (:delta (first out))))
    (is (= 10 (:delta (second out))))
    (is (= 10.0 (:pct_change (second out))))))

(deftest share-of-total
  (let [out (:rows (fnl/run-fn "lib:share" rows {:col "revenue"}))]
    (is (< 99.9 (reduce + (keep :share_pct out)) 100.1))  ; shares sum to 100
    (is (= 25.0 (:share_pct (first out))))))              ; 100 of 400
```

- [ ] **Step 2: Run to verify failure** — unknown function: lib:pivot / lib:delta / lib:share.

- [ ] **Step 3: Implementation**

```clojure
(defn- pivot-run [rows {:keys [rows_col cols_col measure agg]}]
  (let [rk (keyword rows_col) ck (keyword cols_col) mk (keyword measure) agg (or agg "sum")]
    (vec (for [[rv rs] (group-by rk rows)]
           (into {rk rv}
                 (for [[cv cs] (group-by ck rs)]
                   [(keyword (str cv)) (agg-val agg (keep mk cs))]))))))

(defn- delta-run [rows {:keys [col]}]
  (let [k (keyword col)]
    (vec (map (fn [prev r]
                (let [v (get r k) p (when prev (get prev k))]
                  (assoc r
                         :delta (when (and (number? v) (number? p)) (- v p))
                         :pct_change (when (and (number? v) (number? p) (not (zero? p)))
                                       (/ (* 100.0 (- v p)) p)))))
              (cons nil rows) rows))))

(defn- share-run [rows {:keys [col]}]
  (let [k (keyword col) tot (reduce + 0.0 (keep k rows))]
    (vec (map #(assoc % :share_pct (when (and (number? (get % k)) (pos? tot))
                                     (/ (* 100.0 (get % k)) tot)))
              rows))))
```

Registry entries:

```clojure
   {:id "lib:pivot" :label "Pivot" :doc "rows × columns grid — aggregate a measure by two categories"
    :params [{:name "rows_col" :type "catcol"} {:name "cols_col" :type "catcol"}
             {:name "measure" :type "numcol"} {:name "agg" :type "choice" :options ["sum" "avg" "count"]}]
    :pred (fn [rows] (or (needs-numeric rows)
                         (when (< (count (cat-cols rows)) 2) "needs two category columns")))
    :run pivot-run}
   {:id "lib:delta" :label "Change vs previous row" :doc "adds delta and %-change columns, row over row"
    :params [{:name "col" :type "numcol"}]
    :pred (fn [rows] (or (needs-numeric rows) (when (< (count rows) 2) "needs at least two rows")))
    :run delta-run}
   {:id "lib:share" :label "Share of total" :doc "adds each row's % of the column total"
    :params [{:name "col" :type "numcol"}]
    :pred needs-numeric :run share-run}
```

- [ ] **Step 4: Run to verify pass** — `clojure -M:test` → 0 failures.
- [ ] **Step 5: Commit** — `git commit -am "feat: fnlib pivot, delta, share-of-total"`

---

### Task 4: catalog — the palette payload with greyed-out reasons

**Files:**
- Modify: `src/loci/fnlib.clj`
- Test: `test/loci/fnlib_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest catalog-greys-with-reasons-and-fills-options
  (let [cat (fnl/catalog rows)                       ; has numeric + cat cols
        by-id (into {} (map (juxt :id identity)) cat)]
    (is (:ok (by-id "lib:filter")))
    (is (not (:ok (by-id "lib:pivot"))))             ; rows has ONE cat col
    (is (= "needs two category columns" (:why (by-id "lib:pivot"))))
    ;; col params carry the live column options for the UI selects
    (let [p (first (filter #(= "by" (:name %)) (:params (by-id "lib:top"))))]
      (is (= ["revenue" "units"] (:options p)))))
  (let [prose [{:note "a"} {:note "b"}]
        by-id (into {} (map (juxt :id identity)) (fnl/catalog prose))]
    (is (= "needs a numeric column" (:why (by-id "lib:top"))))))
```

- [ ] **Step 2: Run to verify failure** — No such var: `fnl/catalog`.

- [ ] **Step 3: Implementation**

```clojure
(defn catalog
  "The palette for one table: every builtin with :ok/:why (greyed functions
   stay visible — the reason teaches the vocabulary) and col params filled
   with the table's live column options."
  [rows]
  (let [opts {"col"    (mapv name (keys (first rows)))
              "numcol" (mapv name (numeric-cols rows))
              "catcol" (mapv name (cat-cols rows))}]
    (mapv (fn [{:keys [id label doc params pred]}]
            (let [why (when pred (pred rows))]
              {:id id :label label :doc doc :ok (nil? why) :why why
               :params (mapv #(if-let [o (opts (:type %))] (assoc % :options o) %) params)}))
          builtins)))
```

- [ ] **Step 4: Run to verify pass** — `clojure -M:test` → 0 failures.
- [ ] **Step 5: Commit** — `git commit -am "feat: fnlib catalog with compatibility reasons + live col options"`

---

### Task 5: server — /api/fns, /api/fn-preview, /api/fn-apply

**Files:**
- Modify: `src/loci/server.clj` (require `[loci.fnlib :as fnlib]` in the ns form; new fns after `functions-list`; three routes in `handler`)
- Test: `test/loci/server_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(def ^:private trows [{:region "EMEA" :revenue 100} {:region "APAC" :revenue 250}])

(defn- store-with-table []
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value trows}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:intent "i" :cells []}}})
    st))

(deftest fns-payload-lists-builtins-and-agent-fns
  (let [st (store-with-table)]
    (is (:error (srv/fns-payload st "space:n")))          ; not a table
    (sub/commit! st {:op :put :id "fn:t-1"
                     :value {:id "fn:t-1" :kind :fn :title "fn: double it"
                             :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
    (let [ids (set (map :id (:fns (srv/fns-payload st "tbl:t"))))]
      (is (contains? ids "lib:filter"))
      (is (contains? ids "fn:t-1")))))

(deftest fn-preview-commits-nothing
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-preview st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"})]
    (is (= [{:region "APAC" :revenue 250}] (:after r)))
    (is (= 2 (count (:before r))))
    (is (= 1 (:count r)))
    (is (= before (count (sub/history st))))))            ; preview is FREE

(deftest fn-apply-commits-one-tx-with-provenance
  (let [st (store-with-table)
        before (count (sub/history st))
        r (srv/fn-apply! st "tbl:t" "lib:top" {:by "revenue" :n "1" :order "desc"} "space:n")
        nid (:openId r)
        t (sub/object st nid)]
    (is (= (inc before) (count (sub/history st))))        ; ONE event (a :tx)
    (is (= "tbl:t" (:from t)))
    (is (= "lib:top" (:via t)))
    (is (some #(= nid (:ref %)) (get-in (sub/object st "space:n") [:value :cells])))
    (sub/undo! st)                                        ; one undo reverts table AND cell
    (is (nil? (sub/object st nid)))))

(deftest fn-apply-runs-agent-written-sci-fns
  (let [st (store-with-table)]
    (sub/commit! st {:op :put :id "fn:t-1"
                     :value {:id "fn:t-1" :kind :fn :title "fn: double it"
                             :value {:lang "clojure" :code "(fn [rows] (mapv #(update % :revenue * 2) rows))"}}})
    (let [r (srv/fn-apply! st "tbl:t" "fn:t-1" {} nil)]
      (is (= [200 500] (map :revenue (:value (sub/object st (:openId r)))))))))
```

- [ ] **Step 2: Run to verify failure** — No such var: `srv/fns-payload`.

- [ ] **Step 3: Implementation** (insert after `functions-list` in `server.clj`)

```clojure
;; ---- the function palette: built-in single-table verbs (loci.fnlib) plus
;; agent-written :fn objects, previewed live, applied as ONE reversible :tx ----
(defn- table-rows [st id]
  (let [rows (:value (sub/object st id))]
    (when (and (sequential? rows) (seq rows) (every? map? rows)) rows)))

(defn fns-payload [st id]
  (if-let [rows (table-rows st id)]
    {:fns (into (fnlib/catalog rows)
                (->> (sub/objects st) vals
                     (filter #(and (= :fn (:kind %)) (= "clojure" (get-in % [:value :lang]))))
                     (sort-by :id)
                     (mapv (fn [o] {:id (:id o) :label (:title o)
                                    :doc (get-in o [:value :prompt]) :ok true :params []}))))}
    {:error "functions work on tables"}))

(defn- run-any-fn [st fid rows params]
  (if (str/starts-with? fid "fn:")
    (let [o (sub/object st fid)]
      (if-not (= :fn (:kind o))
        {:error (str "unknown function: " fid)}
        (try
          (let [res (sci/eval-string (get-in o [:value :code])
                                     (assoc sci-opts :bindings {'rows (vec rows)}))
                out (json-safe (vec (if (fn? res) (res (vec rows)) res)))]
            (if (and (seq out) (every? map? out))
              {:rows out}
              {:error "the function did not return rows"}))
          (catch Exception e {:error (.getMessage e)}))))
    (let [r (fnlib/run-fn fid rows params)]
      (if (:error r) r {:rows (json-safe (:rows r))}))))

(defn fn-preview [st id fid params]
  (if-let [rows (table-rows st id)]
    (let [r (run-any-fn st fid rows params)]
      (if (:error r)
        r
        {:before (vec (take 8 rows)) :after (vec (take 8 (:rows r))) :count (count (:rows r))}))
    {:error "functions work on tables"}))

(defn fn-apply! [st id fid params space]
  (if-let [rows (table-rows st id)]
    (let [r (run-any-fn st fid rows params)]
      (if (:error r)
        r
        (let [src (sub/object st id)
              lbl (or (:label (first (filter #(= fid (:id %)) fnlib/builtins)))
                      (:title (sub/object st fid)) fid)
              nid (next-id st "tbl:derived-")
              tobj {:id nid :kind :table :title (str (:title src) " · " lbl)
                    :value (:rows r) :from id :via fid}
              evs (cond-> [{:op :put :id nid :value tobj}]
                    (and space (sub/object st space))
                    (conj (nb/append-cell-event st space {:ref nid})))]
          (sub/commit! st {:op :tx :events evs})
          {:state (state-payload st) :openId nid})))
    {:error "functions work on tables"}))
```

Routes (in `handler`, next to `/api/functions`):

```clojure
(= uri "/api/fns")       (json-resp (fns-payload st (params "id")))
(= uri "/api/fn-preview")(let [{:keys [id fnid params]} (body-json req)]
                           (json-resp (fn-preview st id fnid params)))
(= uri "/api/fn-apply")  (let [{:keys [id fnid params space]} (body-json req)]
                           (json-resp (fn-apply! st id fnid params space)))
```

NOTE: `next-id` already exists in server.clj (store-global id minting — do NOT copy the old count-based minting from `compute-clj!`). `sci-opts`/`json-safe` already exist. Add `[loci.fnlib :as fnlib]` to the ns `:require`.

- [ ] **Step 4: Run to verify pass** — `clojure -M:test` → 0 failures.
- [ ] **Step 5: Commit** — `git commit -am "feat: function palette endpoints — fns/preview/apply, one-tx provenance"`

---

### Task 6: frontend — ƒ on the cell: chips → params → preview → apply

**Files:**
- Modify: `resources/public/index.html` (API object; renderNotebook cell header + handler wiring; new `openFnPalette`; CSS)

- [ ] **Step 1: API methods** (in the `API` object)

```js
  fns: id => fetch('/api/fns?id='+encodeURIComponent(id)).then(r=>r.json()),
  fnPreview: (id,fnid,params) => POST('/api/fn-preview',{id,fnid,params}),
  fnApply: (id,fnid,params,space) => POST('/api/fn-apply',{id,fnid,params,space}),
```

- [ ] **Step 2: ƒ button on table cells** — in `renderNotebook`, the `mkable` cell header currently ends with the ✦ button; add ƒ after it:

```js
        (mkable?'<button class="mkcell" data-make="'+esc(c.id)+'" title="✦ make… — ask for a view, a computed table, or a visualization of this data">✦</button>'+
                '<button class="mkcell" data-fnp="'+esc(c.id)+'" title="ƒ apply a function — filter, group, pivot, top-N…">ƒ</button>':'')+'</div>'+
```

And wire it where `[data-make]` is wired:

```js
  b.querySelectorAll('[data-fnp]').forEach(el=>el.addEventListener('click',ev=>{
    ev.stopPropagation();
    openFnPalette(i, el.dataset.fnp, el.closest('.cellmold'));
  }));
```

- [ ] **Step 3: the palette** (new function, place after `makeOn`)

```js
// ƒ on a cell: the function palette, close to the data. Incompatible functions
// stay visible but greyed WITH the reason — the palette teaches the vocabulary.
async function openFnPalette(i, id, anchor){
  const sp=STATE.spaces[i];
  document.querySelectorAll('.fnpal').forEach(el=>el.remove());   // one palette at a time
  let res; try{ res=await API.fns(id); }catch(e){ showToast('palette: network error'); return; }
  if(res.error){ showToast(res.error); return; }
  const host=document.createElement('div'); host.className='fnpal';
  host.innerHTML='<div class="sec-h" style="margin-top:0">ƒ apply a function <button class="mkcell" style="float:right" data-x="close">✕</button></div>'+
    '<div class="fnchips">'+res.fns.map((f,k)=>
      '<button class="fnchip'+(f.ok?'':' dis')+'" data-k="'+k+'" title="'+esc(f.ok?(f.doc||''):f.why)+'">'+esc(f.label)+'</button>').join('')+'</div>'+
    '<div class="fnbody"></div>';
  anchor.insertAdjacentElement('afterend', host);
  host.querySelector('[data-x="close"]').addEventListener('click',()=>host.remove());
  const body=host.querySelector('.fnbody');
  host.querySelectorAll('.fnchip').forEach(ch=>ch.addEventListener('click',()=>{
    const f=res.fns[+ch.dataset.k];
    if(!f.ok){ showToast(f.why); return; }
    host.querySelectorAll('.fnchip').forEach(c=>c.classList.remove('on')); ch.classList.add('on');
    const ctl=p=>{
      if(p.options) return '<label>'+esc(p.name)+' <select data-p="'+esc(p.name)+'">'+
        p.options.map(o=>'<option>'+esc(o)+'</option>').join('')+'</select></label>';
      if(p.type==='num') return '<label>'+esc(p.name)+' <input data-p="'+esc(p.name)+'" type="number" value="10" style="width:64px"></label>';
      return '<label>'+esc(p.name)+' <input data-p="'+esc(p.name)+'" style="width:110px"></label>';
    };
    body.innerHTML='<div class="fnparams">'+(f.params||[]).map(ctl).join('')+
      '<button class="btn ghost sm" data-a="pv">Preview</button>'+
      '<button class="btn sm" data-a="ap">Apply</button></div><div class="fnprev"></div>';
    const collect=()=>{ const ps={}; body.querySelectorAll('[data-p]').forEach(el=>ps[el.dataset.p]=el.value); return ps; };
    const prev=body.querySelector('.fnprev');
    const preview=async()=>{
      prev.innerHTML='<div style="color:var(--muted);font-size:12px">previewing…</div>';
      let r; try{ r=await API.fnPreview(id,f.id,collect()); }catch(e){ prev.innerHTML=''; showToast('preview: network error'); return; }
      if(r.error){ prev.innerHTML='<div style="color:var(--attn);font-size:12px">'+esc(r.error)+'</div>'; return; }
      prev.innerHTML='<div class="fnba"><div><div class="sec-h">before</div>'+tableHTML(r.before)+'</div>'+
        '<div class="fnarrow">→</div><div><div class="sec-h">after · '+r.count+' rows</div>'+tableHTML(r.after)+'</div></div>'+
        '<div style="font-size:11px;color:var(--faint);margin-top:4px">first 8 rows each — nothing is committed until you Apply</div>';
    };
    body.querySelector('[data-a="pv"]').addEventListener('click',preview);
    body.querySelectorAll('[data-p]').forEach(el=>el.addEventListener('change',preview));
    body.querySelector('[data-a="ap"]').addEventListener('click',async()=>{
      let r; try{ r=await API.fnApply(id,f.id,collect(),sp.id); }catch(e){ showToast('apply: network error'); return; }
      if(r.error){ showToast(r.error); return; }
      applyState(r.state); renderBody(i);
      showToast('Applied '+f.label+' — new table in this notebook (reversible)', undo);
    });
  }));
}
```

- [ ] **Step 4: CSS** (next to the `.mkcell` rules)

```css
  .fnpal{border:1px dashed var(--accent-line);border-radius:10px;background:var(--panel);padding:10px 12px;margin:0 0 10px}
  .fnchips{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px}
  .fnchip{border:1px solid var(--line);background:var(--white);border-radius:999px;padding:3px 10px;font-size:12px;cursor:pointer}
  .fnchip:hover{border-color:var(--accent-line)} .fnchip.on{border-color:var(--accent);color:var(--accent)}
  .fnchip.dis{opacity:.45;cursor:not-allowed}
  .fnparams{display:flex;gap:10px;flex-wrap:wrap;align-items:center;font-size:12px;margin-bottom:8px}
  .fnparams select,.fnparams input{border:1px solid var(--line);border-radius:6px;padding:3px 6px;font:inherit;font-size:12px}
  .fnba{display:flex;gap:10px;align-items:flex-start;overflow-x:auto}
  .fnba>div{flex:1;min-width:0} .fnarrow{flex:none;color:var(--accent);padding-top:22px}
```

- [ ] **Step 5: Verify headless** — extract the inline JS and `node --check` it; boot a scratch server on :7778 and curl:
  - `GET /api/fns?id=tbl:countries` → `lib:*` entries, pivot greyed or ok per columns
  - `POST /api/fn-preview {"id":"tbl:countries","fnid":"lib:top","params":{"by":"gdp_usd_bn","n":"5","order":"desc"}}` → before/after, no event-count change in `/api/state`
  - `POST /api/fn-apply` same body + `"space":"space:world"` → derived table id, events +1, cell appended

- [ ] **Step 6: Commit** — `git commit -am "feat: ƒ function palette on table cells — preview before apply"`

---

### Task 7: ƒ inspector gains the library; walkthrough note; final verify

**Files:**
- Modify: `resources/public/index.html` (`openFunctions`)
- Modify: `docs/walkthrough.md`

- [ ] **Step 1: library section in the ƒ modal** — the palette payload is table-relative, so the modal only *advertises* the library with a static heading. In `openFunctions`, prepend before the per-object cards:

```js
  h='<div class="sec-h">built-in library</div><div style="font-size:12.5px;color:var(--muted);margin-bottom:12px">'+
    'Filter rows · Group &amp; aggregate · Pivot · Top N · Change vs previous row · Share of total — '+
    'available on any table cell via <b>ƒ</b>, previewed before anything is committed.</div>'+h;
```

(The palette itself is always table-relative; the modal only advertises it. Agent-written functions keep their cards below, unchanged.)

- [ ] **Step 2: walkthrough** — add to flow 1 in `docs/walkthrough.md`:

```markdown
On any table cell, **ƒ** opens the function palette (filter, group, pivot,
top-N, change, share — plus any functions the agent has written). Pick one,
see a live before/after preview, Apply — the derived table lands as a new
cell, provenance chips intact, one ↺ undo away.
```

- [ ] **Step 3: full verify** — `clojure -M:test` (all green), `node --check` on extracted JS, scratch-server curl pass from Task 6 Step 5 rerun once end-to-end.

- [ ] **Step 4: Commit** — `git commit -am "feat: function palette — library blurb in ƒ inspector, walkthrough update"`

---

## Out of scope (explicitly deferred)

- `join` / any cross-table function (v2 — needs a second-object picker)
- Function composition / chaining, live re-run on input change
- LEAP integration (type a function name → apply to focused object)
- "run on…" buttons inside the ƒ inspector modal (the cell is the flow)
