;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.boundary.issuance
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.boundary.results :as result]
            [ai.miniforge.execution-grant.issuance :as issuance]
            [ai.miniforge.execution-grant.issuance.schema :as input]
            [ai.miniforge.execution-grant.schema :as schema]
            [malli.core :as m])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator input/Arguments))

(def ^{:stratum 0 :private true} valid-grant? (m/validator schema/ExecutionGrant))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} checked-result [value]
  (cond
    (anomaly/anomaly? value) value
    (valid-grant? value) value
    :else (result/invalid-output value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} issue-for-effect
  ([breach-dir request] (issue-for-effect breach-dir request (Instant/now)))
  ([breach-dir request now]
   (let [arguments [request now]]
     (if (valid-arguments? arguments)
       (checked-result (issuance/issue breach-dir request now))
       (result/invalid :issuance/input-invalid input/Arguments arguments)))))

(comment ::only-the-public-interface-consumes-this-boundary)
