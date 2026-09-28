;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.interface
  "API surface class 1: trusted-runtime GitHub PR adapter, EDN in and out.
   JSON is confined to the GitHub boundary. Does not issue or authenticate grants.
   Use only behind the governed coordinator and runtime emergency-stop fence."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-provider-github.creation :as creation]
            [ai.miniforge.opsv-provider-github.messages :as msg]
            [ai.miniforge.opsv-provider-github.observation :as observation]
            [ai.miniforge.opsv-provider-github.schema :as schema]
            [ai.miniforge.opsv-provider-github.transport :as transport]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ProviderRuntime "Trusted runtime configuration contract." schema/ProviderRuntime)

(def ^{:stratum 0} Payload "Exact provider fields in the authorized digest." schema/Payload)

(def ^{:stratum 0} run-command!
  "Default bounded GitHub CLI command port. Runtime may inject a trusted port."
  transport/run-command!)

(defn ^{:stratum 0} create-pr!
  "Provider callback: confirm the head, POST once, then match the response.
   Runtime owns directory, hostname and run-command. The remote branch must be
   exclusively controlled; GitHub does not compare-and-create by expected SHA."
  [runtime claimed payload]
  (if (m/validate schema/CreateArguments [runtime claimed payload])
    (creation/create! runtime payload)
    (anomaly/anomaly :invalid-input (msg/t :input/invalid) {})))

(defn ^{:stratum 0} observe-pr!
  "Read-only callback for an uncertain transaction; never retries a mutation.
   Return an exact observation for reconciliation, or an unavailable anomaly.
   Missing/mismatched/ambiguous PRs are not proof that creation failed."
  [runtime record payload]
  (if (m/validate schema/ObserveArguments [runtime record payload])
    (observation/observe! runtime payload)
    (anomaly/anomaly :invalid-input (msg/t :input/invalid) {})))
