;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-finalization-publication
  "Atomically publish against the validated assembly version; retry concurrent accumulation."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.messages.interface :as messages]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private ts
  (messages/create-translator "config/evidence-bundle/messages/system.edn" :evidence-bundle/system))

(defn- ^{:stratum 0} finalize-current [expected bundle current]
  (if (= expected current)
    (assoc current :opsv.assembly/status :finalized :opsv.assembly/bundle bundle)
    current))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failure [category message-key bundle-id errors]
  (response/make-anomaly category (ts message-key)
                         {:opsv/evidence-bundle-id bundle-id
                          :opsv.validation/errors errors}))

(defn ^{:stratum 1} publish! [store record candidate]
  (let [bundle-id (:evidence-bundle/id record)
        bundle (assoc candidate :evidence/content-hash (hash/content-hash candidate))
        transition (partial finalize-current record bundle)
        [old-state new-state] (swap-vals! store update bundle-id transition)]
    (if (= record (get old-state bundle-id))
      (get-in new-state [bundle-id :opsv.assembly/bundle])
      ::retry)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} immutable [bundle-id]
  (failure :anomalies/conflict :finalization/immutable bundle-id
           [{:code :bundle-already-finalized}]))

(comment
  (immutable (random-uuid)))
