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
          (let [rec (try (edn/read {:eof ::eof} r) (catch Exception _ ::eof))]
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
                    (assoc :source (or (:source opts) (:source dup)))
                    (update :strength (fnil inc 1))
                    (update :entities #(vec (distinct (concat % (:entities opts))))))
                (do
                  ;; count-based ids are collision-free ONLY while memory is
                  ;; append-only (reinforce keeps ids; nothing ever deletes)
                  {:id (str "mem-" (inc (count @!facts)))
                   :fact fact :entities (vec (:entities opts))
                   :source (:source opts) :ts (System/currentTimeMillis) :strength 1}))]
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
