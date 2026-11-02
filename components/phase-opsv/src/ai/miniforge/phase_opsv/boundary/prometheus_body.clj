;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-body
  "Admit exactly one unambiguous JSON document from bounded UTF-8 bytes."
  (:require [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.json-document :as json]
            [ai.miniforge.phase-opsv.boundary.measurement :as measurement]
            [ai.miniforge.phase-opsv.measurement-window :as window]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} decode! [settings request until body]
  (let [limit (:prometheus/max-response-bytes settings)
        bytes (io/read-bytes! body limit until)]
    (if (> (alength bytes) limit)
      (window/invalid-response)
      (measurement/decode-response request (json/decode! (io/utf8-text bytes))))))

(comment ::complete-native-json-response)
