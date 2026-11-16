;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.atomic-publication
  "Publication primitives; callers own source forcing and destination confirmation."
  (:import [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} replace-options
  "Do not fall back to a non-atomic move."
  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))

(defn ^{:stratum 0} link! [^File candidate ^File destination]
  (Files/createLink (.toPath destination) (.toPath candidate))
  destination)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} replace! [^File candidate ^File destination]
  (Files/move (.toPath candidate) (.toPath destination) replace-options)
  destination)
