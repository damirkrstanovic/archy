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
   any caller changing — the same seam we used for `Recall`.")

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
