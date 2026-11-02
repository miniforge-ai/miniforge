;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-schema
  (:require [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private response-byte-ceiling (dec Integer/MAX_VALUE))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Settings
  [:map {:closed true}
   [:prometheus/base-url schema/NonBlankString]
   [:prometheus/bearer-token {:optional true} [:re #"\A[A-Za-z0-9._~+/-]+=*\z"]]
   [:prometheus/timeout-ms [:int {:min 1
                                 :max Integer/MAX_VALUE}]]
   [:prometheus/max-response-bytes [:int {:min 1
                                         :max response-byte-ceiling}]]
   [:measurement/max-samples [:int {:min 2}]]])

(comment ::prometheus-operational-settings)
