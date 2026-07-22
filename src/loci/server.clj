(ns loci.server
  "Layer 5 backend: the substrate + mold layer served as JSON to the loci shell.

   The HTTP boundary is the substrate/assistance seam — everything the frontend
   shows is molded by `loci.mold` on the Clojure side; the frontend just lays it
   out. (Same code path the headless demo exercises.)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [sci.core :as sci]
            [org.httpkit.server :as http]
            [loci.agent :as agent]
            [loci.content :as c]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.notebook :as nb]
            [loci.substrate :as sub]
            [loci.tools :as tools]
            [loci.viewspec :as vs]))

(defn store [] @c/store)

;; ---- keyword <-> string for view ids that round-trip to the browser ----
(defn- kw->str [k] (if (namespace k) (str (namespace k) "/" (name k)) (name k)))
(defn- str->kw [s] (if (str/includes? s "/")
                     (let [[n nm] (str/split s #"/" 2)] (keyword n nm))
                     (keyword s)))

(defn- json-resp [data]
  {:status 200
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/write-str data)})

(defn- parse-query [qs]
  (into {} (for [pair (some-> qs (str/split #"&")) :when (seq pair)]
             (let [[k v] (str/split pair #"=" 2)]
               [k (some-> v (java.net.URLDecoder/decode "UTF-8"))]))))

(defn- body-json [req] (when-let [b (:body req)] (json/read-str (slurp b) :key-fn keyword)))

;; ---- payloads ----
(defn state-payload [st]
  (let [objs (vals (sub/objects st))]
    {:events  (count (sub/history st))
     :spaces  (->> objs (filter #(= :space (:kind %)))
                   (map (fn [s] {:id (:id s) :title (:title s)
                                 :intent (get-in s [:value :intent])
                                 :members (vec (keep :ref (nb/cells-of s)))}))
                   vec)
     :objects (->> objs (remove #(#{:space :viewspec :applet :fn} (:kind %)))
                   (map (fn [o] {:id (:id o) :title (:title o) :kind (name (:kind o))}))
                   vec)}))

(defn dynamic-views [st target]
  (->> (sub/objects st) vals
       (filter #(and (#{:viewspec :applet} (:kind %)) (= target (get-in % [:value :target]))))))

(defn- alternatives [st o]
  (vec (concat (mapv (fn [[vid lbl]] [(kw->str vid) lbl]) (:alternatives (mold/mold (:value o))))
               (mapv (fn [v] [(:id v) (get-in v [:value :label])]) (dynamic-views st (:id o))))))

(defn mold-payload [st id view]
  (let [o (sub/object st id)]
    (cond
      ;; an agent-WRITTEN code view — ship the JS + the data it runs over
      (and view (str/starts-with? view "app:"))
      (let [vo (sub/object st view)]
        {:id id :title (:title o) :kind "applet" :view view
         :label (get-in vo [:value :label]) :code (get-in vo [:value :code])
         :rendered (:value o) :alternatives (alternatives st o)})
      ;; an agent-proposed view-spec — interpret it deterministically
      (and view (str/starts-with? view "view:"))
      (let [vo (sub/object st view)
            {:keys [kind rendered]} (vs/interpret (get-in vo [:value :spec]) (:value o))]
        {:id id :title (:title o) :kind (name kind) :view view
         :label (get-in vo [:value :label]) :rendered rendered :alternatives (alternatives st o)})
      ;; a built-in viewer
      :else
      (let [m (mold/mold (:value o) (when (seq view) (str->kw view)))]
        {:id id :title (:title o) :kind (name (:kind m)) :view (when (:view m) (kw->str (:view m)))
         :label (:label m) :rendered (:rendered m) :alternatives (alternatives st o)}))))

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
  (let [o (sub/object st space)]
    (cond
      (not (= :space (:kind o)))
      {:error (str "not a notebook: " space)}
      :else
      (let [cells  (nb/cells-of o)
            cells' (nb/cell-op cells body)]
        ;; unknown op / stale idx fall through unchanged — don't commit a phantom event
        (when (not= cells cells')
          (sub/commit! st (nb/set-cells-event space cells')))
        {:state (state-payload st) :notebook (notebook-payload st space)}))))

;; ---- writes (every one a substrate event) ----
(defn edit! [st id value]
  (sub/commit! st {:op :assoc :id id :path [:value] :value value})
  {:state (state-payload st) :object (mold-payload st id nil)})

;; (1) molding-by-description — the agent emits a view-spec stored as a
;; reversible object; it then appears in the object's "view this as…" menu.
(defn describe! [st id prompt]
  (try
    (let [o (sub/object st id) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "describe works on tables"}
        (let [spec (agent/describe-view (mapv name (keys (first rows))) (take 2 rows) prompt)
              n (count (filter #(= :viewspec (:kind %)) (vals (sub/objects st))))
              vid (str "view:" (subs id (inc (str/index-of id ":"))) "-" (inc n))
              vobj {:id vid :kind :viewspec :title (str "view: " (:label spec))
                    :value {:target id :label (or (:label spec) prompt) :spec spec}}]
          (sub/commit! st {:op :put :id vid :value vobj})
          (assoc (mold-payload st id vid) :events (count (sub/history st))))))
    (catch Exception e {:error (.getMessage e)})))

;; (1b) molding-by-CODE — the agent writes a self-contained JS applet that runs
;; over the object's rows. Stored as a reversible :applet object; appears in the
;; "view this as…" menu and executes in the shell. ponytail: code runs in the
;; page (new Function) — fine for a local single-user prototype; sandbox/iframe
;; if this ever serves untrusted users.
(defn make-view! [st id prompt]
  (try
    (let [o (sub/object st id) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "code views work on tables"}
        (let [code (agent/make-applet (mapv name (keys (first rows))) (take 3 rows) prompt)
              n (count (filter #(= :applet (:kind %)) (vals (sub/objects st))))
              p (str/trim prompt)
              vid (str "app:" (subs id (inc (str/index-of id ":"))) "-" (inc n))
              vobj {:id vid :kind :applet :title (str "app: " p)
                    :value {:target id :code code
                            :label (str "▶ " (if (> (count p) 26) (str (subs p 0 26) "…") p))}}]
          (sub/commit! st {:op :put :id vid :value vobj})
          (assoc (mold-payload st id vid) :events (count (sub/history st))))))
    (catch Exception e {:error (.getMessage e)})))

;; (1d) function-as-substrate-object, BACKEND flavour — the agent writes a pure
;; Clojure (fn [rows] …), run SCI-SANDBOXED on the JVM (only clojure.core + Math;
;; no I/O, no interop). Computation stays in the substrate's own language; we have
;; the result server-side, so the :fn AND the derived table commit in ONE :tx
;; (atomic, single undo). The browser only renders.
(def ^:private sci-opts {:classes {'Math java.lang.Math}})
(defn- json-safe [rows] (walk/postwalk #(if (ratio? %) (double %) %) rows))

(defn compute-clj! [st id prompt space]
  (try
    (let [o (sub/object st id) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "compute works on tables"}
        (let [code (agent/make-clj-transform (mapv name (keys (first rows))) (take 3 rows) prompt)
              ;; accept either `(fn [rows] …)` or a bare expression over `rows`
              res  (sci/eval-string code (assoc sci-opts :bindings {'rows (vec rows)}))
              out  (json-safe (vec (if (fn? res) (res (vec rows)) res)))]
          (if-not (and (seq out) (every? map? out))
            {:error "the function did not return rows"}
            (let [nf (count (filter #(= :fn (:kind %)) (vals (sub/objects st))))
                  nt (count (filter #(= :table (:kind %)) (vals (sub/objects st))))
                  p  (str/trim prompt)
                  fid (str "fn:" (subs id (inc (str/index-of id ":"))) "-" (inc nf))
                  nid (str "tbl:derived-" (inc nt))
                  ttl (if (> (count p) 48) (str (subs p 0 48) "…") p)
                  fobj {:id fid :kind :fn :title (str "fn: " p)
                        :value {:source id :prompt p :lang "clojure" :code code}}
                  tobj {:id nid :kind :table :title ttl :value out :from id :via fid}
                  evs (cond-> [{:op :put :id fid :value fobj}
                               {:op :put :id nid :value tobj}]
                        (and space (sub/object st space))
                        (conj (nb/append-cell-event st space {:ref nid})))]
              (sub/commit! st {:op :tx :events evs})
              {:state (state-payload st) :openId nid :object (mold-payload st nid nil)})))))
    (catch Exception e {:error (str "compute failed: " (.getMessage e))})))

;; the functions living in the substrate, with their code — an inspector for the
;; curious. NOT the everyday flow; the everyday flow is just using the verbs.
(defn functions-list [st]
  (->> (sub/objects st) vals
       (filter #(#{:fn :applet :viewspec} (:kind %)))
       (sort-by :id)
       (mapv (fn [o] {:id (:id o) :kind (name (:kind o)) :title (:title o)
                      :lang (or (get-in o [:value :lang])
                                (case (:kind o) :applet "js" :viewspec "view-spec" nil))
                      :target (or (get-in o [:value :source]) (get-in o [:value :target]))
                      :prompt (get-in o [:value :prompt])
                      :code (or (get-in o [:value :code])
                                (when (= :viewspec (:kind o))
                                  (pr-str (get-in o [:value :spec]))))}))))

;; ONE verb over a table: the user says what they want, the agent picks the
;; simplest mechanism (declarative view-spec / browser applet / server-side
;; Clojure transform) and routes to it. No technique buttons — loci decides.
(defn do! [st id prompt space]
  (try
    (let [o (sub/object st id) rows (:value o)]
      (if-not (and (sequential? rows) (seq rows) (every? map? rows))
        {:error "this works on tables"}
        (let [technique (agent/choose-technique (mapv name (keys (first rows))) (take 3 rows) prompt)]
          (case technique
            "transform" (let [r (compute-clj! st id prompt space)]
                          (if (:error r) r (assoc r :technique "transform")))
            "applet"    (let [r (make-view! st id prompt)]
                          (if (:error r) r {:technique "applet" :state (state-payload st) :object r :openId id}))
            (let [r (describe! st id prompt)]   ; "view" (default)
              (if (:error r) r {:technique "view" :state (state-payload st) :object r :openId id}))))))
    (catch Exception e {:error (.getMessage e)})))

;; delegate! is defined below (after obj-digest) — it is tool-powered.

;; the agent sets up a new space (intention) and gathers relevant members
(defn new-space! [st prompt]
  (try
    (let [catalog (->> (sub/objects st) vals (remove #(#{:space :viewspec :applet :fn} (:kind %)))
                       (mapv (fn [o] {:id (:id o) :title (:title o) :kind (name (:kind o))})))
          ids (set (map :id catalog))
          spec (try (agent/plan-space catalog prompt) (catch Exception _ nil))
          title (or (not-empty (:title spec)) prompt)
          intent (or (not-empty (:intent spec)) prompt)
          members (vec (filter ids (or (:members spec) [])))
          n (count (filter #(= :space (:kind %)) (vals (sub/objects st))))
          sid (str "space:new-" (inc n))]
      (sub/commit! st {:op :put :id sid
                       :value {:id sid :kind :space :title title
                               :value {:intent intent :members members}}})
      {:state (state-payload st) :spaceId sid})
    (catch Exception e {:error (.getMessage e)})))

;; ask — answer a question grounded in a compact digest of the workspace
(defn- table-digest
  "Per-column aggregates so the agent can answer questions about a big table
   without seeing every row: numeric sum/avg/min/max, and counts for low-
   cardinality categorical columns."
  [rows]
  (let [cols    (keys (first rows))
        numcols (filter #(number? (get (first rows) %)) cols)
        catcols (filter #(and (string? (get (first rows) %))
                              (let [d (count (distinct (map % rows)))] (and (> d 1) (<= d 12))))
                        cols)
        nums (for [c numcols]
               (let [xs (map #(get % c) rows)]
                 (str "  " (name c) ": sum=" (reduce + xs)
                      " avg=" (Math/round (double (/ (reduce + xs) (count xs))))
                      " min=" (apply min xs) " max=" (apply max xs))))
        cats (for [c catcols]
               (str "  " (name c) " counts: " (json/write-str (frequencies (map #(get % c) rows)))))
        cross (when (and (seq numcols) (seq catcols))
                (let [m (first numcols)]
                  (for [c catcols]
                    (str "  " (name m) " by " (name c) ": "
                         (json/write-str (into {} (map (fn [[k rs]] [k (reduce + (map #(get % m) rs))])
                                                        (group-by c rows))))))))]
    (str/join "\n" (concat nums cats cross))))

(defn- obj-digest [o]
  (let [v (:value o) id (:id o) t (:title o)]
    (cond
      (string? v) (str "## " id " — " t " (doc)\n" v)
      (and (sequential? v) (seq v) (every? :block v))
      (str "## " id " — " t " (report)\n"
           (str/join "\n" (keep #(case (:block %) :heading (str "### " (:text %)) :text (:text %) nil) v)))
      (and (sequential? v) (seq v) (every? map? v))
      (str "## " id " — " t " (table, " (count v) " rows; cols: "
           (str/join ", " (map name (keys (first v)))) ")\n"
           (table-digest v) "\nsample rows: " (json/write-str (vec (take 3 v))))
      :else "")))

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

(defn ask! [st prompt space]
  (try
    (let [objs    (if-let [sp (and space (sub/object st space))]
                    (keep #(sub/object st (:ref %)) (nb/cells-of sp))  ; scoped to this space
                    (vals (sub/objects st)))                                  ; whole workspace
          objs    (remove #(#{:space :viewspec :applet :fn} (:kind %)) objs)
          texty   (remove #(and (sequential? (:value %)) (seq (:value %)) (every? map? (:value %))
                                (not (every? :block (:value %)))) objs) ; docs + reports (not tables)
          tbls    (filter #(= :table (:kind %)) objs)
          allowed (when space (set (map :id tbls)))
          catalog (str/join "\n" (map (fn [o] (str "- " (:id o) " — " (:title o)
                                                   " (cols: " (str/join ", " (map name (keys (first (:value o)))))
                                                   "; " (count (:value o)) " rows)")) tbls))
          sys     (str "Answer the user's question about their workspace. Cite the object ids you used. "
                       "Do NOT guess any table figure — call query_table for exact numbers. "
                       "If the data doesn't say, say so plainly. Be concise, markdown.\n\n"
                       "DOCS:\n" (str/join "\n\n" (map obj-digest texty))
                       "\n\nTABLES (query these by id):\n" catalog
                       (remembered-context prompt))
          tool-fn (fn [nm a]
                    (if (and allowed (= nm "query_table") (not (allowed (:table_id a))))
                      {:error "that table is not in this space"}
                      (tools/dispatch st nm a)))]
      (let [answer (agent/chat-tools [{:role "system" :content sys} {:role "user" :content prompt}]
                                     tools/specs tool-fn)]
        (distill! prompt answer nil space)
        {:answer answer}))
    (catch Exception e {:error (.getMessage e)})))

(defn import-csv! [st title csv space]
  (try
    (let [rows (tools/parse-csv csv)
          nid (str "tbl:csv-" (count (filter #(= :table (:kind %)) (vals (sub/objects st)))) "-" (inc (rand-int 9999)))
          obj {:id nid :kind :table :title (or (not-empty title) "Imported CSV") :value rows}
          base [{:op :put :id nid :value obj}]
          evs (if (and space (sub/object st space))
                (conj base (nb/append-cell-event st space {:ref nid}))
                base)]
      (sub/commit! st {:op :tx :events evs})
      {:state (state-payload st) :openId nid})
    (catch Exception e {:error (.getMessage e)})))

(defn keep-note! [st space title text]
  (let [sp  (sub/object st space)
        n   (count (filter #(str/starts-with? % "note:") (nb/refs-of sp)))
        nid (str "note:" (subs space (inc (str/index-of space ":"))) "-" (inc n))]
    (sub/commit! st {:op :tx :events [{:op :put :id nid :value {:id nid :kind :doc :title title :value text}}
                                      (nb/append-cell-event st space {:ref nid})]})
    {:state (state-payload st) :openId nid}))

;; (2) tool-powered delegation — the agent drafts a brief, calling query_table
;; for exact figures (and web_search once a key is set), grounded in the space.
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

;; shared agent context for a space (docs inline + table catalog + scoped tools)
(defn- agent-ctx [st space]
  (let [objs (if-let [sp (and space (sub/object st space))]
               (keep #(sub/object st (:ref %)) (nb/cells-of sp))
               (vals (sub/objects st)))
        objs (remove #(#{:space :viewspec :applet :fn} (:kind %)) objs)
        texty (remove #(= :table (:kind %)) objs)
        tbls  (filter #(= :table (:kind %)) objs)
        allowed (when space (set (map :id tbls)))
        catalog (str/join "\n" (map (fn [o] (str "- " (:id o) " — " (:title o)
                                                 " (cols: " (str/join ", " (map name (keys (first (:value o)))))
                                                 "; " (count (:value o)) " rows)")) tbls))]
    {:context (str "DOCS:\n" (str/join "\n\n" (map obj-digest texty))
                   "\n\nTABLES (query by id):\n" catalog)
     :tool-fn (fn [nm a]
                (cond
                  (= nm "save_table")
                  (tools/save-table! st (:title a) (:rows a) space)
                  (and allowed (= nm "query_table") (not (allowed (:table_id a))))
                  {:error "that table is not in this space"}
                  :else (tools/dispatch st nm a)))}))

;; Research — the agent gathers (web + your data) and LANDS the findings as a
;; durable, moldable, reversible object in the space.
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

;; deep-dive: the agent proposes subtopics (grounded in the hub's findings AND
;; recalled memory), each spawned as a connected notebook and researched.
(defn deep-dive! [st space]
  (try
    (let [sp (sub/object st space)]
      (if-not (= :space (:kind sp))
        {:error (str "not a notebook: " space)}
        (let [digest (str (->> (nb/cells-of sp) (keep #(sub/object st (:ref %)))
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
          {:state (state-payload st) :spawned spawned})))
    (catch Exception e {:error (.getMessage e)})))

;; ---- routing ----
(defn handler [{:keys [uri query-string] :as req}]
  (let [st (store)
        params (parse-query query-string)]
    (cond
      (= uri "/")            {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                              :body (slurp (io/resource "public/index.html"))}
      (= uri "/api/state")   (json-resp (state-payload st))
      (= uri "/api/mold")    (json-resp (mold-payload st (params "id") (params "view")))
      (= uri "/api/leap")    (json-resp (leap-payload st @mem/memory (params "q")))
      (= uri "/api/undo")    (do (sub/undo! st) (json-resp (state-payload st)))
      (= uri "/api/edit")    (let [{:keys [id value]} (body-json req)] (json-resp (edit! st id value)))
      (= uri "/api/delegate")(let [{:keys [space]} (body-json req)] (json-resp (delegate! st space)))
      (= uri "/api/research")(let [{:keys [space prompt]} (body-json req)] (json-resp (research! st space prompt)))
      (= uri "/api/do")      (let [{:keys [id prompt space]} (body-json req)] (json-resp (do! st id prompt space)))
      (= uri "/api/functions")(json-resp (functions-list st))
      (= uri "/api/new-space")(let [{:keys [prompt]} (body-json req)] (json-resp (new-space! st prompt)))
      (= uri "/api/ask")     (let [{:keys [prompt space]} (body-json req)] (json-resp (ask! st prompt space)))
      (= uri "/api/keep-note")(let [{:keys [space title text]} (body-json req)] (json-resp (keep-note! st space title text)))
      (= uri "/api/import-csv")(let [{:keys [title csv space]} (body-json req)] (json-resp (import-csv! st title csv space)))
      (= uri "/api/notebook")(if (= :post (:request-method req))
                               (json-resp (notebook-op! st (body-json req)))
                               (json-resp (notebook-payload st (params "id"))))
      (= uri "/api/links")   (json-resp (nb/links st (params "space")))
      (= uri "/api/memory")  (json-resp {:facts (let [qq (params "q")]
                                                  (if (seq qq)
                                                    (mold/recall @mem/memory qq {:k 20})
                                                    (mem/all-facts @mem/memory)))})
      (= uri "/api/deep-dive")(if (= :post (:request-method req))
                                (json-resp (deep-dive! st (:space (body-json req))))
                                {:status 405 :headers {"Content-Type" "text/plain"} :body "POST only"})
      (str/starts-with? uri "/api/object/")
      (json-resp (mold-payload st (java.net.URLDecoder/decode (subs uri (count "/api/object/")) "UTF-8") nil))
      :else {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})))

(defonce server (atom nil))

(defn -main [& _]
  (let [port 7777]
    (reset! server (http/run-server #'handler {:port port}))
    (println (str "loci shell on http://localhost:" port "  (substrate: "
                  (count (sub/history (store))) " events)"))
    @(promise)))
