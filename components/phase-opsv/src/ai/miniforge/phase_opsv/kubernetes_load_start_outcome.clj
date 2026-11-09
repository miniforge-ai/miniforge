;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-outcome
  "Confirm conditional gate removal only; a receipt does not prove traffic, SLOs or recovery."
  (:require [ai.miniforge.phase-opsv.kubernetes-effect-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-effect-outcome :as outcome]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-schema :as ownership]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-observation?
  (m/validator [:and ownership/Pod
                [:map ["spec" [:map ["schedulingGates" {:optional true} [:= []]]]]]]))

(defn ^{:stratum 0} unconfirmed []
  (outcome/failure :unknown-outcome :load/start-unconfirmed))

(defn ^{:stratum 0} refused []
  (outcome/failure :failed :load/start-submission-refused))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} report [record pod]
  (if (and (valid-observation? pod) (binding/same-pod? record pod))
    (outcome/acknowledged {:kubernetes/resource (binding/reference record)
                           :kubernetes/resource-version (get-in pod ["metadata" "resourceVersion"])
                           :load/gate-release-acknowledged? true})
    (unconfirmed)))

(comment
  (report {} {}))
