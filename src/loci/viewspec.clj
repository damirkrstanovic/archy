(ns loci.viewspec
  "Deterministic interpreter for agent-proposed view-specs.

   The agent emits a small declarative spec (group-by / measure / agg / filter /
   sort / limit / columns / kind). This namespace *executes* it against rows —
   no model in the loop, fully reproducible. That split (agent proposes the
   spec, the substrate runs it) is what keeps molding-by-description trustworthy."
  (:require [clojure.string :as str]))

(defn- num [x] (if (number? x) x (try (Double/parseDouble (str x)) (catch Exception _ nil))))
(defn- round2 [x] (Double/parseDouble (String/format java.util.Locale/US "%.2f" (to-array [(double x)]))))
(defn- kw [x] (when (and x (not (str/blank? (str x)))) (keyword (str x))))

(defn interpret [spec rows]
  (let [gb      (kw (:group_by spec))
        measure (kw (:measure spec))
        agg     (or (:agg spec) "sum")
        kind    (keyword (or (:kind spec) "table"))
        rows    (if-let [{:keys [col op val]} (:filter spec)]
                  (let [c (kw col)]
                    (filterv (fn [r] (let [v (get r c)]
                                       (case op
                                         "=" (= (str v) (str val))
                                         ">" (boolean (when-let [a (num v)] (when-let [b (num val)] (> a b))))
                                         "<" (boolean (when-let [a (num v)] (when-let [b (num val)] (< a b))))
                                         true)))
                             rows))
                  (vec rows))
        ycol    (name (or measure (keyword agg)))
        agged   (if gb
                  (->> rows (group-by gb)
                       (mapv (fn [[g rs]]
                               (let [vs (keep #(num (get % measure)) rs)
                                     m  (case agg
                                          "count" (count rs)
                                          "avg"   (round2 (/ (reduce + vs) (double (max 1 (count vs)))))
                                          (round2 (reduce + vs)))]
                                 (array-map (name gb) (str g) ycol m)))))
                  rows)
        sorted  (if-let [s (:sort spec)]
                  (let [key (if gb ycol (or (some-> measure name) (name (first (keys (first agged))))))
                        ordered (sort-by #(or (num (get % (keyword key))) (str (get % (keyword key)))) agged)]
                    (vec (if (= s "desc") (reverse ordered) ordered)))
                  agged)
        limited (if-let [l (:limit spec)] (vec (take l sorted)) sorted)
        out     (if-let [cols (:columns spec)] (mapv #(select-keys % (map keyword cols)) limited) limited)]
    (case kind
      :chart {:kind :chart
              :rendered {:chart (or (:chart spec) "bar")
                         :x (name (or gb (first (keys (first out)))))
                         :y (if gb ycol (name (last (keys (first out)))))
                         :rows out}}
      :list  {:kind :list  :rendered (mapv (fn [r] (str/join " — " (map str (vals r)))) out)}
      :cards {:kind :cards :rendered out}
      {:kind :table :rendered out})))
