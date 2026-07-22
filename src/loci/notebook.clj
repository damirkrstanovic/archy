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
