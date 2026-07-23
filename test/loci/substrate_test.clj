(ns loci.substrate-test
  (:require [clojure.test :refer [deftest is]]
            [loci.substrate :as sub]))

(defn- tmpfile []
  (str (System/getProperty "java.io.tmpdir") "/loci-test-" (System/nanoTime) "/substrate.edn"))

(deftest persistent-store-replays
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "hello"}})
    (sub/commit! s {:op :assoc :id "a" :path [:value] :value "world"})
    (is (= "world" (:value (sub/object s "a"))))
    ;; a brand-new store over the same file replays to identical state
    (let [s2 (sub/persistent-store path)]
      (is (= 2 (count (sub/history s2))))
      (is (= "world" (:value (sub/object s2 "a"))))
      (is (= (sub/state s) (sub/state s2))))))

(deftest persistent-undo-truncates-file
  (let [path (tmpfile)
        s    (sub/persistent-store path)]
    (sub/commit! s {:op :put :id "a" :value {:id "a" :value 1}})
    (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
    (sub/undo! s)
    (is (nil? (sub/object s "b")))
    (let [s2 (sub/persistent-store path)]
      (is (= 1 (count (sub/history s2))))
      (is (nil? (sub/object s2 "b"))))))

(deftest append-after-reload-preserves-order
  (let [path (tmpfile)]
    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
    ;; a reloaded store appends after what it replayed
    (let [s (sub/persistent-store path)]
      (sub/commit! s {:op :put :id "b" :value {:id "b" :value 2}})
      (let [s2 (sub/persistent-store path)]
        (is (= [:put :put] (mapv :op (sub/history s2))))
        (is (= 1 (:value (sub/object s2 "a"))))
        (is (= 2 (:value (sub/object s2 "b"))))))))

(deftest frozen-store-is-a-read-only-window
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "a" :value {:id "a" :kind :doc :title "A" :value "1"}})
    (sub/commit! st {:op :put :id "b" :value {:id "b" :kind :doc :title "B" :value "2"}})
    (sub/commit! st {:op :assoc :id "a" :path [:value] :value "1'"})
    (let [fz (sub/frozen-at st 2)]
      ;; sees exactly the first 2 events
      (is (= "1" (:value (sub/object fz "a"))))          ; edit (event 3) not applied
      (is (= "B" (:title (sub/object fz "b"))))
      (is (= 2 (count (sub/history fz))))
      ;; the present is untouched
      (is (= "1'" (:value (sub/object st "a"))))
      ;; the past cannot be edited
      (is (thrown? UnsupportedOperationException (sub/commit! fz {:op :put :id "c" :value {}})))
      (is (thrown? UnsupportedOperationException (sub/undo! fz)))
      ;; as-of within the window still works (nested time travel)
      (is (nil? (sub/object (sub/frozen-at fz 1) "b"))))))

(deftest corrupt-trailing-line-salvages-prefix
  (let [path (tmpfile)]
    (sub/commit! (sub/persistent-store path) {:op :put :id "a" :value {:id "a" :value 1}})
    (spit path "{:op :put :id \"b\" :va" :append true)   ; simulate crash mid-append
    (let [s (sub/persistent-store path)]
      (is (= 1 (count (sub/history s))))
      (is (= 1 (:value (sub/object s "a")))))))
