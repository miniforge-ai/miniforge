;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.interface
  "API surface class 1: trusted-runtime GitHub PR adapter, EDN in and out.
   JSON is confined to the GitHub boundary. Does not issue or authenticate grants.
   Use only behind the governed coordinator and runtime emergency-stop fence."
  (:require [ai.miniforge.opsv-provider-github.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ProviderRuntime "Trusted runtime configuration contract." schema/ProviderRuntime)

(def ^{:stratum 0} Payload "Exact provider fields in the authorized digest." schema/Payload)
