;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-permissions
  "POSIX publication policy; other providers retain their native defaults."
  (:require [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} private-permissions "rw-------")

(defn ^{:stratum 0} posix? [path]
  (try+
    (fs/posix-file-permissions path {:nofollow-links true})
    true
    (catch UnsupportedOperationException _ false)))

(comment ::checkpoint-permission-policy)
