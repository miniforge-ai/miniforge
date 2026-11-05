;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-fingerprint-schema
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Text [:and :string [:fn (complement str/blank?)]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Images [:map-of {:min 1} Text Text])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Fingerprint
  [:map {:closed true}
   [:cluster Text]
   [:node-pools [:vector {:min 1} Text]]
   [:image-digests Images]
   [:config-hash Text]])

(comment ::canonical-opsv-fingerprint-values)
