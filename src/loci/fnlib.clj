(ns loci.fnlib
  "Built-in single-table transforms — simple-Excel verbs, close to the data.
   A function is DATA {:id :label :doc :params :pred :run}; the registry is a
   vector. UI-free: catalog and run-fn work over plain rows, any shell renders."
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
