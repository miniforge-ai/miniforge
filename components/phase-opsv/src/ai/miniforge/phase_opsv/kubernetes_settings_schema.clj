;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-settings-schema
  (:require [ai.miniforge.phase-opsv.http-schema :as http]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Settings
  [:map {:closed true}
   [:kubernetes/base-url schema/NonBlankString]
   [:kubernetes/bearer-token {:optional true} http/BearerToken]
   [:kubernetes/cluster-id schema/NonBlankString]
   [:kubernetes/namespace names/NamespaceName]
   [:kubernetes/pod-selector schema/NonBlankString]
   [:kubernetes/node-pool-label schema/NonBlankString]
   [:kubernetes/timeout-ms http/TimeoutMillis]
   [:kubernetes/max-response-bytes http/ResponseBytes]
   [:kubernetes/max-resources [:int {:min 1 :max Integer/MAX_VALUE}]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} NamespaceInventorySettings
  (-> Settings
      (mu/dissoc :kubernetes/pod-selector)
      (mu/dissoc :kubernetes/node-pool-label)))

(def ^{:stratum 1} VerificationSettings (mu/dissoc Settings :kubernetes/pod-selector))

(comment ::explicit-kubernetes-acquisition-settings)
