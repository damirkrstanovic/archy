(ns loci.substrate
  "Layer 1: the deterministic substrate — an append-only event log.

   Every change (human or agent) is one event appended to the log; the current
   state is a left-fold of the log. That single shape buys the three Raskin
   non-negotiables for free:

     · universal undo   = drop the last event, re-materialize
     · audit / history  = the log *is* the audit trail
     · time-travel      = materialize a prefix (`as-of`)

   This is an in-process implementation so it stays verifiable with no external
   DB. It sits behind the `Store` protocol exactly so a real immutable,
   time-aware engine (XTDB / Datahike / Datomic) can replace it later without
   any caller changing — the same seam we used for `Recall`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defprotocol Store
  (commit! [this event] "append an event map; returns the new tx count")
  (state   [this]       "the materialized current state {:objects {id -> obj}}")
  (objects [this]       "map of id -> object")
  (object  [this id]    "one object by id")
  (history [this]       "the full event log (vector, oldest first)")
  (undo!   [this]       "revert the last commit; returns tx count")
  (as-of   [this n]     "materialized state after the first n events"))

(defmulti apply-event
  "How one event transforms state. Open for extension (an agent's new op is a
   new method, not a fork of the reducer)."
  (fn [_state event] (:op event)))

(defmethod apply-event :put     [st {:keys [id value]}] (assoc-in st [:objects id] value))
(defmethod apply-event :assoc   [st {:keys [id path value]}] (assoc-in st (into [:objects id] path) value))
(defmethod apply-event :delete  [st {:keys [id]}] (update st :objects dissoc id))
;; a transaction — several sub-events committed atomically as ONE undoable step
(defmethod apply-event :tx      [st {:keys [events]}] (reduce apply-event st events))
(defmethod apply-event :default [st _] st)

(defn materialize [events]
  (reduce apply-event {:objects {}} events))

(defrecord EventStore [!log]
  Store
  (commit! [_ event]
    (swap! !log conj (assoc event :ts (System/currentTimeMillis)))
    (count @!log))
  (state   [_] (materialize @!log))
  (objects [this] (:objects (state this)))
  (object  [this id] (get (objects this) id))
  (history [_] @!log)
  (undo!   [_] (swap! !log (fn [l] (cond-> l (seq l) pop))) (count @!log))
  (as-of   [_ n] (materialize (take n @!log))))

(defn fresh-store [] (->EventStore (atom [])))

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
