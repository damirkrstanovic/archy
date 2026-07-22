(ns user
  "Dev entrypoint. Starts Clerk and opens the loci notebook.

   REPL:        clj -M:dev   then  (require 'user) (user/start)
   One-shot:    clojure -X:start"
  (:require [nextjournal.clerk :as clerk]))

(defn start [& _]
  (clerk/serve! {:browse? true :watch-paths ["notebooks"]})
  (clerk/show! "notebooks/loci.clj")
  (println "loci notebook served — Clerk is watching notebooks/")
  @(promise)) ;; block so `-X:start` stays alive
