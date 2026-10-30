;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.signature
  "The runtime's signature over a grant's issued fields.

   Revocation is outside the signature: the store records it as a separate
   marker after issue, and a revoked grant must still verify as the one the
   runtime issued. Deleting that marker is not detected here."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.runtime-signing.interface :as signing]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} issued-fields
  [grant]
  (dissoc grant :grant/signature :grant/revoked-at :grant/revocation-reason))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sign
  "`grant` carrying the runtime signature of its issued fields, or the
   anomaly that kept the runtime key from loading."
  [grant]
  (let [key-bytes (signing/load-or-create-key!)]
    (if (anomaly/anomaly? key-bytes)
      key-bytes
      (assoc grant :grant/signature (signing/sign key-bytes (issued-fields grant))))))

(defn ^{:stratum 1} signed?
  "True only when `grant` carries this runtime's signature over its issued
   fields. False when the key cannot load."
  [grant]
  (let [key-bytes (signing/load-or-create-key!)]
    (and (map? grant)
         (not (anomaly/anomaly? key-bytes))
         (signing/signed? key-bytes (issued-fields grant) (:grant/signature grant)))))

(comment
  (signed? {}))
