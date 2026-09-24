;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.messages
  "Catalogs for OPSV provider content and boundary diagnostics."
  (:require [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} t
  (messages/create-translator "config/opsv-actuation/messages/en-US.edn"
                              :opsv-actuation/messages))

(def ^{:stratum 0} ts
  (messages/create-translator "config/opsv-actuation/messages/system.edn"
                              :opsv-actuation/system))
