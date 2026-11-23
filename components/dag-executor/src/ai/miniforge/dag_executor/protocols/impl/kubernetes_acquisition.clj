;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-acquisition
  "Carry confirmed submission data through cancellation-fenced acquisition."
  (:require [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} interrupted []
  (let [error (result/err :pod-interrupted (messages/t :kubernetes/pod-interrupted))]
    (.interrupt (Thread/currentThread))
    error))

(defn- ^{:stratum 0} cleanup-failure! [cleanup! observed]
  (cleanup!)
  observed)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} finish! [observe! cleanup! retain! created]
  (let [observed (if (.isInterrupted (Thread/currentThread)) (interrupted) (observe!))]
    (cond
      (or (.isInterrupted (Thread/currentThread))
          (= :pod-interrupted (get-in observed [:error :code]))) (interrupted)
      (result/err? observed) (cleanup-failure! cleanup! observed)
      :else (retain! created (:data observed)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} acquire!
  "Keep legacy failure cleanup, but fence submission, cleanup and retention on cancellation."
  [submit! observe! cleanup! retain!]
  (try+
    (if (.isInterrupted (Thread/currentThread))
      (interrupted)
      (result/and-then (submit!) (partial finish! observe! cleanup! retain!)))
    (catch InterruptedException _ (interrupted))))

(comment
  (acquire! (constantly (result/ok :submitted))
            (constantly (result/ok :running))
            (constantly nil)
            (fn [submitted observed] (result/ok [submitted observed]))))
