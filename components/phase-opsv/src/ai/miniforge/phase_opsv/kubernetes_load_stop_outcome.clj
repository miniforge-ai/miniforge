;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-outcome
  "Confirm only the conditional deadline patch, not termination or successful recovery."
  (:require [ai.miniforge.phase-opsv.kubernetes-effect-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-schema :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-stop :as stop]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-observation?
  (m/validator [:and ownership/Pod
                [:map ["spec" [:map ["activeDeadlineSeconds" [:= stop/stop-deadline-seconds]]]]]]))

(defn- ^{:stratum 0} same-resource? [record pod]
  (let [reference (binding/reference record)
        metadata (get pod "metadata")]
    (= (select-keys reference [:name :namespace :uid])
       {:name (get metadata "name") :namespace (get metadata "namespace") :uid (get metadata "uid")})))

(defn- ^{:stratum 0} failure [outcome message-key]
  (let [message (msg/ts message-key)]
    {:effect/outcome outcome :effect/failure message}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unconfirmed []
  (failure :unknown-outcome :load/stop-unconfirmed))

(defn ^{:stratum 1} refused []
  (failure :failed :load/stop-submission-refused))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} report [record pod]
  (if (and (valid-observation? pod) (same-resource? record pod))
    {:effect/outcome :succeeded
     :effect/observed {:kubernetes/resource (binding/reference record)
                       :kubernetes/resource-version (get-in pod ["metadata" "resourceVersion"])
                       :load/deadline-seconds stop/stop-deadline-seconds}}
    (unconfirmed)))

(comment ::actual-owned-container-quiescence-and-baseline-recovery-must-still-be-observed-separately)
