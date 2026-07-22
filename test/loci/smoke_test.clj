(ns loci.smoke-test
  "Proves the test runner is wired. Kept as a canary."
  (:require [clojure.test :refer [deftest is]]
            [loci.substrate :as sub]))

(deftest store-round-trip
  (let [s (sub/fresh-store)]
    (sub/commit! s {:op :put :id "x" :value {:id "x" :value 1}})
    (is (= 1 (:value (sub/object s "x"))))
    (sub/undo! s)
    (is (nil? (sub/object s "x")))))
