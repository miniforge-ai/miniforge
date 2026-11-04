;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.artifact-fixtures)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private producer-version
  "Version used by OPSV phase material records passed to artifact/build-artifact."
  "1.0.0")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} artifact [type]
  ;; Mirrors artifact/build-artifact; importing artifact here would create a brick cycle.
  {:artifact/id (random-uuid)
   :artifact/type type
   :artifact/version producer-version
   :artifact/content {}
   :artifact/parents []
   :artifact/metadata {}})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} minimal-artifact []
  (-> (artifact :spec)
      (dissoc :artifact/content :artifact/parents :artifact/metadata)
      (assoc :artifact/version "0.1.0")))

(comment ::artifact-envelope-fixtures-not-domain-content-validation)
