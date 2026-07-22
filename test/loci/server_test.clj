(ns loci.server-test
  (:require [clojure.test :refer [deftest is]]
            [loci.memory :as mem]
            [loci.mold :as mold]
            [loci.server :as srv]
            [loci.substrate :as sub]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/memory.edn"))

(deftest leap-searches-everything
  (let [st (sub/fresh-store)
        m  (mem/file-memory (tmpfile))]
    (sub/commit! st {:op :put :id "doc:x" :value {:id "doc:x" :kind :doc :title "Pricing notes"
                                                  :value "The plan rose 18% in March."}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "Notebook N"
                                                    :value {:intent "i"
                                                            :cells [{:text "churn concentrated in young accounts"}]}}})
    (mold/remember m "Outreach recovers about 22% of downgrades." {:entities ["retention"]})
    (let [groups (fn [q] (set (map :group (srv/leap-payload st m q))))]
      (is (contains? (groups "churn") "prose"))        ; cell prose
      (is (contains? (groups "march") "in text"))      ; doc body, title doesn't match
      (is (contains? (groups "downgrades") "memory"))  ; memory fact
      (is (contains? (groups "notebook") "space")))))  ; notebook by title
