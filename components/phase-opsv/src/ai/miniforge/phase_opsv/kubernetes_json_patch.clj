;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-json-patch
  "Shared conditional wire vocabulary, not authority or caller-controlled HTTP.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} media-type "RFC 6902, not strategic merge or server-side apply." "application/json-patch+json")

(def ^{:stratum 0} uid-path "Fence replacement objects even when a name is reused." "/metadata/uid")

(def ^{:stratum 0} revision-path "Fence changes made since the fresh capture." "/metadata/resourceVersion")

(defn- ^{:stratum 0} operation [op path value]
  {"op" op
   "path" path
   "value" value})

(defn ^{:stratum 0} remove-value [path]
  {"op" "remove"
   "path" path})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} test-value [path value] (operation "test" path value))

(defn ^{:stratum 1} replace-value [path value] (operation "replace" path value))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} metadata-tests [metadata]
  (let [uid (get metadata "uid")
        revision (get metadata "resourceVersion")]
    [(test-value uid-path uid) (test-value revision-path revision)]))

(comment
  (metadata-tests {"uid" "owned-uid"
                   "resourceVersion" "fresh-revision"})
  (remove-value "/spec/schedulingGates"))
