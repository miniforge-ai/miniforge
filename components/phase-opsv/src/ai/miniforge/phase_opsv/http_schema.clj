;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.http-schema
  "Shared HTTP wire and bounded-read settings.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} BearerToken [:re #"\A[A-Za-z0-9._~+/-]+=*\z"])

(def ^{:stratum 0} TimeoutMillis [:int {:min 1 :max Integer/MAX_VALUE}])

(def ^{:stratum 0} ^:private response-byte-ceiling
  "Leave one integer-addressable sentinel byte for overflow detection."
  (dec Integer/MAX_VALUE))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ResponseBytes [:int {:min 1 :max response-byte-ceiling}])

(comment ::bounded-http-settings)
