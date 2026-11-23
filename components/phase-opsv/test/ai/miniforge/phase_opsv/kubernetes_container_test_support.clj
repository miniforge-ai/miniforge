;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-container-test-support
  "Kubernetes API declaration and observed-status factories.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} container [name]
  {"name" name
   "image" "registry.invalid/service:release"})

(defn ^{:stratum 0} status [name image-id]
  {"name" name
   "imageID" image-id})

(comment ::container-api-shapes)
