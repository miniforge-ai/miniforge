;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-http-test-support
  (:require [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.kubernetes-reference-test-support :as target]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} resource-path "/api/v1/namespaces/staging")

(def ^{:stratum 0} ^:private test-timeout-ms
  "Bound deterministic in-memory reads without constraining a busy test host."
  10000)

(defn- ^{:stratum 0} record-request! [pending calls options]
  (swap! calls conj options)
  pending)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-pending!
  ([overrides pending until] (read-pending! overrides pending until (atom [])))
  ([overrides pending until calls]
   (let [options (settings/load-settings (target/settings overrides))]
     (with-redefs [transport/start! (partial record-request! pending calls)
                   transport/response-fields identity]
       [(http/read-resource! options until resource-path {}) @calls]))))

(defn ^{:stratum 1} deadline [] (io/deadline test-timeout-ms))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-response! [overrides response]
  (read-pending! overrides (wire/completed response) (deadline)))

(comment ::native-kubernetes-http-fixtures)
