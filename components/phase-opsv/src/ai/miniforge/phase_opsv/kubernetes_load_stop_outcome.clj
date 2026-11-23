;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-outcome
  "Confirm only the conditional deadline patch, not termination or successful recovery."
  (:require [ai.miniforge.phase-opsv.kubernetes-effect-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-effect-outcome :as outcome]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-schema :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-stop :as stop]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-observation?
  (m/validator [:and ownership/Pod
                [:map ["spec" [:map ["activeDeadlineSeconds" [:= stop/stop-deadline-seconds]]]]]]))

(defn ^{:stratum 0} unconfirmed []
  (outcome/failure :unknown-outcome :load/stop-unconfirmed))

(defn ^{:stratum 0} refused []
  (outcome/failure :failed :load/stop-submission-refused))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} report [record pod]
  (if (and (valid-observation? pod) (binding/same-pod? record pod))
    (outcome/acknowledged {:kubernetes/resource (binding/reference record)
                           :kubernetes/resource-version (get-in pod ["metadata" "resourceVersion"])
                           :load/deadline-seconds stop/stop-deadline-seconds})
    (unconfirmed)))

(comment ::actual-owned-container-quiescence-and-baseline-recovery-must-still-be-observed-separately)
