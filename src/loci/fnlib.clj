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

(def builtins
  [{:id "lib:filter" :label "Filter rows" :doc "keep rows where a column passes a test"
    :params [{:name "col" :type "col"}
             {:name "op" :type "choice" :options ["=" "≠" ">" "<" "contains"]}
             {:name "value" :type "text"}]
    :pred (fn [_] nil) :run filter-run}
   {:id "lib:group" :label "Group & aggregate" :doc "one row per group — sum/avg/min/max/count a column"
    :params [{:name "by" :type "col"} {:name "measure" :type "numcol"}
             {:name "agg" :type "choice" :options ["sum" "avg" "min" "max" "count"]}]
    :pred needs-numeric :run group-run}
   {:id "lib:top" :label "Top N" :doc "sort by a numeric column and keep the first N"
    :params [{:name "by" :type "numcol"} {:name "n" :type "num"}
             {:name "order" :type "choice" :options ["desc" "asc"]}]
    :pred needs-numeric :run top-run}])

(defn run-fn [fid rows params]
  (if-let [f (first (filter #(= fid (:id %)) builtins))]
    (try
      (let [out ((:run f) rows params)]
        (if (and (seq out) (every? map? out))
          {:rows out}
          {:error "no rows came out — nothing to keep"}))
      (catch Exception e {:error (.getMessage e)}))
    {:error (str "unknown function: " fid)}))
