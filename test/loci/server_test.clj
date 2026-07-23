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

(deftest notebook-op-rejects-non-notebooks
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:cells []}}})
    ;; non-space target: rejected, nothing committed, state still materializes
    (is (:error (srv/notebook-op! st {:space "tbl:t" :op "add-text" :text "x"})))
    (is (:error (srv/notebook-op! st {:space "nope" :op "add-text" :text "x"})))
    (is (= 2 (count (sub/history st))))
    ;; unknown op: no phantom event committed
    (srv/notebook-op! st {:space "space:n" :op "bogus"})
    (is (= 2 (count (sub/history st))))
    ;; a real op still works
    (srv/notebook-op! st {:space "space:n" :op "add-text" :text "hi"})
    (is (= 3 (count (sub/history st))))
    (is (= [{:text "hi"}] (get-in (sub/object st "space:n") [:value :cells])))))

(deftest edit-rejects-missing-id
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "doc:a" :value {:id "doc:a" :kind :doc :title "A" :value "x"}})
    (is (:error (srv/edit! st nil "v")))
    (is (:error (srv/edit! st "nope" "v")))
    (is (= 1 (count (sub/history st))))          ; nothing committed
    (is (nil? (:error (srv/edit! st "doc:a" "y"))))
    (is (= "y" (:value (sub/object st "doc:a"))))))

(defn- await-job [id]
  (loop [n 0]
    (let [s (srv/job-status id)]
      (if (or (:done s) (> n 100))
        s
        (do (Thread/sleep 50) (recur (inc n)))))))

(deftest jobs-run-async-and-surface-results
  (let [id (srv/start-job! (fn [] {:x 1}))]
    (is (string? id))
    (let [s (await-job id)]
      (is (:done s))
      (is (= {:x 1} (:result s))))))

(deftest jobs-surface-thrown-errors
  (let [s (await-job (srv/start-job! (fn [] (throw (Exception. "boom")))))]
    (is (:done s))
    (is (= "boom" (get-in s [:result :error])))))

(deftest unknown-job-is-done-with-error
  (let [s (srv/job-status "job:nope")]
    (is (:done s))                 ; a poller must stop, not spin forever
    (is (:error s))))

(deftest deep-dive-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "tbl:t" :value {:id "tbl:t" :kind :table :title "T" :value [{:a 1}]}})
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/deep-dive-start! st "tbl:t")))     ; not a notebook
    (is (:error (srv/deep-dive-start! st "nope")))      ; missing
    (with-redefs [srv/deep-dive! (fn [_ _] {:spawned ["space:dd-1"]})]
      (let [{:keys [job error]} (srv/deep-dive-start! st "space:h")]
        (is (nil? error))
        (is (= ["space:dd-1"] (get-in (await-job job) [:result :spawned])))))))

(deftest research-start-validates-then-runs-as-job
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (is (:error (srv/research-start! st "nope" "q")))   ; missing notebook
    (is (:error (srv/research-start! st "space:h" " "))) ; blank prompt
    (with-redefs [srv/research! (fn [_ _ _] {:openId "find:h-1"})]
      (let [{:keys [job error]} (srv/research-start! st "space:h" "q")]
        (is (nil? error))
        (is (= "find:h-1" (get-in (await-job job) [:result :openId])))))))

(deftest state-payload-exposes-spawned-by
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:h" :value {:id "space:h" :kind :space :title "Hub" :value {:intent "i" :cells []}}})
    (sub/commit! st {:op :put :id "space:dd-1"
                     :value {:id "space:dd-1" :kind :space :title "Child"
                             :value {:intent "sub" :cells []
                                     :spawned-by {:space "space:h" :prompt "q"}}}})
    (let [by-id (into {} (map (juxt :id identity)) (:spaces (srv/state-payload st)))]
      (is (= "space:h" (get-in by-id ["space:dd-1" :spawned-by])))
      (is (nil? (get-in by-id ["space:h" :spawned-by]))))))

(deftest note-ids-survive-cell-removal
  (let [st (sub/fresh-store)]
    (sub/commit! st {:op :put :id "space:n" :value {:id "space:n" :kind :space :title "N" :value {:cells []}}})
    (srv/keep-note! st "space:n" "one" "1")
    (srv/keep-note! st "space:n" "two" "2")
    (srv/keep-note! st "space:n" "three" "3")
    ;; remove the MIDDLE note's cell (idx 1) — note:n-2's object lives on
    (srv/notebook-op! st {:space "space:n" :op "remove" :idx 1})
    (srv/keep-note! st "space:n" "four" "4")
    ;; the new note must NOT reuse an existing id
    (is (= "3" (:value (sub/object st "note:n-3"))))   ; old note untouched
    (is (= "4" (:value (sub/object st "note:n-4"))))))  ; new note got a fresh id
