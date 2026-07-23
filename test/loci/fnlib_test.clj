(ns loci.fnlib-test
  (:require [clojure.test :refer [deftest is]]
            [loci.fnlib :as fnl]))

(def rows [{:region "EMEA" :revenue 100 :units 5}
           {:region "APAC" :revenue 250 :units 2}
           {:region "EMEA" :revenue 50  :units 9}])

(deftest column-helpers
  (is (= [:revenue :units] (fnl/numeric-cols rows)))
  (is (= [:region] (fnl/cat-cols rows))))

(deftest filter-numeric-and-string
  (is (= [{:region "APAC" :revenue 250 :units 2}]
         (:rows (fnl/run-fn "lib:filter" rows {:col "revenue" :op ">" :value "150"}))))
  (is (= 2 (count (:rows (fnl/run-fn "lib:filter" rows {:col "region" :op "=" :value "EMEA"})))))
  (is (= 2 (count (:rows (fnl/run-fn "lib:filter" rows {:col "region" :op "contains" :value "em"})))))
  ;; zero matches is an honest error, not an empty table in the substrate
  (is (:error (fnl/run-fn "lib:filter" rows {:col "region" :op "=" :value "MARS"}))))

(deftest unknown-function-errors
  (is (:error (fnl/run-fn "lib:nope" rows {}))))
