;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-schema
  (:require [ai.miniforge.phase-opsv.http-schema :as http]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Settings
  [:map {:closed true}
   [:prometheus/base-url schema/NonBlankString]
   [:prometheus/bearer-token {:optional true} http/BearerToken]
   [:prometheus/timeout-ms http/TimeoutMillis]
   [:prometheus/max-response-bytes http/ResponseBytes]
   [:measurement/max-samples [:int {:min 2}]]])

(comment ::prometheus-operational-settings)
