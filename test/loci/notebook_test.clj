(ns loci.notebook-test
  (:require [clojure.test :refer [deftest is]]
            [loci.notebook :as nb]
            [loci.substrate :as sub]))

(deftest cells-normalization
  ;; legacy :members upgrade to ref cells; :cells pass through
  (is (= [{:ref "a"} {:ref "b"}] (nb/cells-of {:value {:members ["a" "b"]}})))
  (is (= [{:text "hi"} {:ref "a" :view "table/bar"}]
         (nb/cells-of {:value {:cells [{:text "hi"} {:ref "a" :view "table/bar"}]}}))))

(deftest cell-ops
  (let [cells [{:text "a"} {:ref "t1"}]]
    (is (= [{:text "a"} {:ref "t1"} {:text "new"}]
           (nb/cell-op cells {:op "add-text" :text "new"})))
    (is (= [{:text "a"} {:ref "t1"} {:ref "t2" :view "table/bar"}]
           (nb/cell-op cells {:op "add-ref" :ref "t2" :view "table/bar"})))
    (is (= [{:text "edited"} {:ref "t1"}]
           (nb/cell-op cells {:op "edit-text" :idx 0 :text "edited"})))
    (is (= [{:text "a"} {:ref "t1" :view "table/pivot"}]
           (nb/cell-op cells {:op "set-view" :idx 1 :view "table/pivot"})))
    (is (= [{:text "a"} {:ref "t1"}]
           (nb/cell-op (nb/cell-op cells {:op "set-view" :idx 1 :view "table/pivot"})
                       {:op "set-view" :idx 1 :view ""})))   ; empty view clears back to default
    (is (= [{:ref "t1"}] (nb/cell-op cells {:op "remove" :idx 0})))
    (is (= [{:ref "t1"} {:text "a"}] (nb/cell-op cells {:op "move" :idx 0 :to 1})))))

(deftest links-reasons
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:x" :value {:id "tbl:x" :kind :table :title "X" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "tbl:d" :value {:id "tbl:d" :kind :table :title "D" :value [{:a 2}]
                                                  :from "tbl:x" :via "fn:x-1"}})
    (sub/commit! st {:op :put :id "space:hub" :value {:id "space:hub" :kind :space :title "Hub"
                                                      :value {:cells [{:ref "tbl:x"}]}}})
    (sub/commit! st {:op :put :id "space:sub" :value {:id "space:sub" :kind :space :title "Sub"
                                                      :value {:cells [{:ref "tbl:x"}]
                                                              :spawned-by {:space "space:hub" :prompt "p"}}}})
    (sub/commit! st {:op :put :id "space:der" :value {:id "space:der" :kind :space :title "Der"
                                                      :value {:cells [{:ref "tbl:d"}]}}})
    (let [{:keys [connected also-in]} (nb/links st "space:hub")
          by-id (into {} (map (juxt :id identity) connected))]
      (is (= #{"space:sub" "space:der"} (set (keys by-id))))
      (is (= #{{:type "spawned"} {:type "shares" :obj "tbl:x"}}
             (set (:reasons (by-id "space:sub")))))
      (is (= [{:type "derived" :obj "tbl:x"}] (:reasons (by-id "space:der"))))
      (is (= [{:id "space:sub" :title "Sub"}] (get also-in "tbl:x"))))))
