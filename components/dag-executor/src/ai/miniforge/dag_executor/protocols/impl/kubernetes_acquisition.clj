;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-acquisition
  "Compose legacy acquisition callbacks without claiming immutable Job ownership."
  (:require [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} interrupted []
  (let [error (result/err :pod-interrupted (messages/t :kubernetes/pod-interrupted))]
    (.interrupt (Thread/currentThread))
    error))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} finish! [observe! cleanup! retain!]
  (let [observed (observe!)]
    (cond
      (or (.isInterrupted (Thread/currentThread))
          (= :pod-interrupted (get-in observed [:error :code]))) (interrupted)
      (result/err? observed) (do (cleanup!) observed)
      :else (retain! (:data observed)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} acquire!
  "Keep legacy failure cleanup, but fence submission, cleanup and retention on cancellation."
  [submit! observe! cleanup! retain!]
  (try+
    (if (.isInterrupted (Thread/currentThread))
      (interrupted)
      (result/and-then (submit!)
                       (fn [_] (finish! observe! cleanup! retain!))))
    (catch InterruptedException _ (interrupted))))

(comment
  (acquire! (constantly (result/ok :submitted))
            (constantly (result/ok :running))
            (constantly nil)
            result/ok))
