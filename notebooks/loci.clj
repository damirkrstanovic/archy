;; # loci — populated workspace
;; Layers **1** (event-log substrate), **2** (content) and **4** (mold) are
;; standing. Everything below reads from the seeded substrate store. Clerk is
;; only the render target — the molding logic lives in `loci.mold`, UI-free.
^{:nextjournal.clerk/visibility {:code :hide}}
(ns loci.notebook
  (:require [clojure.datafy :as d]
            [nextjournal.clerk :as clerk]
            [loci.content :as c]
            [loci.mold :as mold]
            [loci.substrate :as sub]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def store @c/store)

;; ---- interactive molder: "view this as…" (a reagent widget) ----
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def molder
  {:render-fn
   '(fn [{:keys [label views default]}]
      (reagent.core/with-let [!sel (reagent.core/atom (or default 0))]
        (let [cur  (nth views @!sel nil)
              line "#d6dad3" ink "#1B211E" muted "#6d7872" paper "#ECEEEA"]
          [:div {:style {:border (str "1px solid " line) :border-radius "12px"
                         :overflow "hidden" :background "#fff" :font-family "ui-sans-serif, system-ui"}}
           [:div {:style {:display "flex" :align-items "center" :gap "10px"
                          :padding "10px 14px" :background paper :border-bottom (str "1px solid " line)}}
            [:span {:style {:font-weight "600" :color ink}} label]
            [:span {:style {:margin-left "auto" :font-size "12px" :color muted}} "view this as"]
            [:select {:value @!sel
                      :on-change #(reset! !sel (js/parseInt (.. % -target -value)))
                      :style {:padding "4px 8px" :border-radius "7px" :border (str "1px solid " line)}}
             (map-indexed (fn [i v] [:option {:key i :value i} (:label v)]) views)]]
           [:div {:style {:padding "14px"}}
            (case (:kind cur)
              :table (let [rows (:data cur) cols (keys (first rows))]
                       [:table {:style {:border-collapse "collapse" :width "100%" :font-size "13px"}}
                        [:thead [:tr (for [col cols]
                                       [:th {:key (str col)
                                             :style {:text-align "left" :padding "4px 8px"
                                                     :border-bottom (str "1px solid " line)
                                                     :font-family "monospace" :font-size "11px" :color muted}}
                                        (name col)])]]
                        [:tbody (map-indexed
                                 (fn [i r] [:tr {:key i}
                                            (for [col cols]
                                              [:td {:key (str col)
                                                    :style {:padding "4px 8px" :border-bottom "1px solid #eee"}}
                                               (str (get r col))])])
                                 rows)]])
              :list [:ul {:style {:margin "0" :padding-left "18px"}}
                     (map-indexed (fn [i s] [:li {:key i :style {:margin "3px 0"}} s]) (:data cur))]
              :text [:div {:style {:white-space "pre-wrap" :font-size "13px" :line-height "1.5"}} (:data cur)]
              :chart (let [rows (:data cur) W 460 H 180 pad 28
                           months (vec (distinct (map :month rows)))
                           xi (zipmap months (range))
                           maxy (apply max 1 (map :churn rows))
                           sx (fn [m] (+ pad (* (/ (get xi m) (max 1 (dec (count months)))) (- W (* 2 pad)))))
                           sy (fn [y] (- H pad (* (/ y maxy) (- H (* 2 pad)))))
                           colors ["#2f6f5b" "#a9632f"]]
                       [:svg {:width W :height H}
                        (map-indexed
                         (fn [i [_ rs]]
                           (let [rs  (sort-by :month rs)
                                 pts (apply str (interpose " " (map (fn [r] (str (sx (:month r)) "," (sy (:churn r)))) rs)))
                                 col (nth colors (mod i (count colors)))]
                             [:g {:key i}
                              [:polyline {:points pts :fill "none" :stroke col :stroke-width "2"}]
                              (for [r rs] [:circle {:key (:month r) :cx (sx (:month r)) :cy (sy (:churn r)) :r "3" :fill col}])]))
                         (group-by :tenure-band rows))])
              [:pre (pr-str (:data cur))])]])))})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn molder-of [id]
  (let [o (sub/object store id)
        {:keys [alternatives]} (mold/mold (:value o))]
    (clerk/with-viewer molder
      {:label (:title o) :default 0
       :views (mapv (fn [[vid vlabel]]
                      (let [m (mold/mold (:value o) vid)]
                        {:label vlabel :kind (:kind m) :data (:rendered m)}))
                    alternatives)})))

;; ---- LEAP: incremental find across everything ----
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def leap
  {:render-fn
   '(fn [entries]
      (reagent.core/with-let [!q (reagent.core/atom "")]
        (let [q     (.toLowerCase @!q)
              line  "#d6dad3" accent "#2f6f5b" muted "#6d7872"
              shown (filter (fn [e] (or (= q "")
                                        (.includes (.toLowerCase (str (:label e) " " (:id e) " " (:group e))) q)))
                            entries)]
          [:div {:style {:border (str "1px solid " line) :border-radius "12px" :overflow "hidden"
                         :background "#fff" :max-width "560px" :font-family "ui-sans-serif, system-ui"}}
           [:div {:style {:display "flex" :align-items "center" :gap "10px" :padding "12px 14px"
                          :border-bottom (str "1px solid " line)}}
            [:span {:style {:font-family "monospace" :font-size "11px" :color accent
                            :border (str "1px solid " accent) :border-radius "6px" :padding "2px 7px"}} "LEAP"]
            [:input {:value @!q :placeholder "type to find anything…"
                     :on-change #(reset! !q (.. % -target -value))
                     :style {:border "0" :outline "none" :font-size "15px" :flex "1"}}]
            [:span {:style {:font-size "12px" :color muted}} (count shown)]]
           [:div {:style {:max-height "260px" :overflow "auto" :padding "6px 0"}}
            (for [e (take 40 shown)]
              [:div {:key (:id e) :style {:display "flex" :align-items "center" :gap "10px" :padding "7px 14px"}}
               [:span {:style {:font-family "monospace" :font-size "10px" :color muted :width "62px" :flex "none"}} (:group e)]
               [:span {:style {:font-size "14px"}} (:label e)]
               [:span {:style {:margin-left "auto" :font-family "monospace" :font-size "10px" :color muted}} (:id e)]])]])))})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def leap-entries
  (vec (concat (->> (sub/objects store) vals
                    (map (fn [o] {:id (:id o) :label (:title o) :group (name (:kind o))})))
               (->> @mold/registry
                    (map (fn [v] {:id (str (:id v)) :label (str "view as " (:label v)) :group "viewer"}))))))

;; ## LEAP — finding is navigating
;; The same gesture spans content, spaces, and the agent's view-verbs. Type to filter.
^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/with-viewer leap leap-entries)

;; ## Spaces — intentions, not folders
^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/html
 (into [:div]
       (for [s (->> (sub/objects store) vals (filter #(= :space (:kind %))) (sort-by :title))]
         [:div {:style {:border "1px solid #d6dad3" :border-radius "12px" :padding "14px 16px"
                        :margin-bottom "12px" :background "#F7F8F5"}}
          [:div {:style {:font-weight "600" :font-size "16px"}} (:title s)]
          [:div {:style {:color "#6d7872" :font-size "13px" :margin "3px 0 9px"}} (get-in s [:value :intent])]
          (into [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px"}}]
                (for [m (c/members store (:id s))]
                  [:span {:style {:font-family "monospace" :font-size "11px" :border "1px solid #c8cdc5"
                                  :border-radius "5px" :padding "2px 7px" :background "#fff"}}
                   (:id m)]))])))

;; ## One object, molded many ways
;; Each widget is the **same stored object**; the dropdown re-molds it. Notice the
;; menus differ — the revenue table offers a cohort and a chart because its shape
;; earns them; the themes table only offers Rows. Molding is *contextual*.

;; ### `tbl:revenue` — Rows · Churn cohort · Churn over time
^{:nextjournal.clerk/visibility {:code :hide}}
(molder-of "tbl:revenue")

;; ### `tbl:themes` — a plainer table, fewer molds
^{:nextjournal.clerk/visibility {:code :hide}}
(molder-of "tbl:themes")

;; ### `doc:churn` — Full text · Outline
^{:nextjournal.clerk/visibility {:code :hide}}
(molder-of "doc:churn")

;; ## Moldable navigation (datafy / nav)
;; A ticket turns itself into data and navigates into its account — no view code.
^{:nextjournal.clerk/visibility {:code :hide}}
(let [t (d/datafy c/sample-ticket)]
  {:ticket t :account (d/nav t :account-id (:account-id t))})

;; ## The substrate — the event log
;; Every object arrived as an appended event. Universal undo = drop the last one.
^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/table
 {:head ["#" "op" "id"]
  :rows (map-indexed (fn [i e] [(inc i) (name (:op e)) (:id e)]) (sub/history store))})
