;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-diagnostics
  "Canonical OPSV assembly failure reports and localized diagnostics."
  (:require [ai.miniforge.messages.interface :as messages]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private ts
  (messages/create-translator "config/evidence-bundle/messages/system.edn" :evidence-bundle/system))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failure [category message-key bundle-id errors]
  (response/make-anomaly category (ts message-key)
                         {:opsv/evidence-bundle-id bundle-id
                          :opsv.validation/errors errors}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} immutable [bundle-id]
  (failure :anomalies/conflict :finalization/immutable bundle-id
           [{:code :bundle-already-finalized}]))

(comment
  (immutable (random-uuid)))
