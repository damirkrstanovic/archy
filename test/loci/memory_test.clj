(ns loci.memory-test
  (:require [clojure.test :refer [deftest is]]
            [loci.memory :as mem]
            [loci.mold :as mold]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/memory.edn"))

(deftest remember-and-recall-ranked
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "TSMC produces most leading-edge logic chips."
                   {:entities ["tsmc" "taiwan"] :source {:obj "find:semis-1" :space "space:semis"}})
    (mold/remember m "Serbia's inflation spiked to about 12% in 2022."
                   {:entities ["serbia"] :source {:obj "doc:serbia-econ" :space "space:serbia"}})
    (let [r (mold/recall m "taiwan chips" {:k 5})]
      (is (= 1 (count r)))                                   ; only the relevant fact scores
      (is (= "find:semis-1" (get-in (first r) [:source :obj])))
      (is (pos? (:score (first r)))))))

(deftest near-duplicate-reinforces
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "TSMC produces most leading-edge logic chips." {:entities ["tsmc"]})
    (mold/remember m "TSMC produces most of the leading-edge logic chips." {:entities ["taiwan"]})
    (let [fs (mem/all-facts m)]
      (is (= 1 (count fs)))
      (is (= 2 (:strength (first fs))))
      (is (= #{"tsmc" "taiwan"} (set (:entities (first fs))))))))

(deftest reload-is-last-wins-by-id
  (let [path (tmpfile)
        m    (mem/file-memory path)]
    (mold/remember m "MRR compounds monthly at a steady rate." {:entities ["mrr"]})
    (mold/remember m "MRR compounds monthly at a steady pace." {:entities ["finance"]})
    ;; reinforcement appended a second line with the same :id — reload dedupes
    (let [fs (mem/all-facts (mem/file-memory path))]
      (is (= 1 (count fs)))
      (is (= 2 (:strength (first fs)))))))

(deftest recall-ranks-competing-facts
  (let [m (mem/file-memory (tmpfile))]
    (mold/remember m "Taiwan produces advanced chips for global markets" {:entities ["taiwan"]})
    (mold/remember m "Japan supplies chemicals for semiconductor production" {:entities ["japan"]})
    ;; both mention chips and production; taiwan fact matches the query's entity → ranks first
    (is (= ["Taiwan produces advanced chips for global markets"
            "Japan supplies chemicals for semiconductor production"]
           (mapv :fact (mold/recall m "taiwan chips production" {:k 5}))))
    ;; reinforcing the japan fact (near-duplicate wording) boosts its strength…
    (mold/remember m "Japan supplies key chemicals for semiconductor production" {:entities ["japan"]})
    ;; …so for a neutral query it now outranks the unreinforced fact
    (is (= "mem-2" (:id (first (mold/recall m "chemicals production" {:k 5})))))))
