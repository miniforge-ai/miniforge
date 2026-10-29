;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-permissions
  "POSIX publication policy; other providers retain their native defaults."
  (:require [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]])
  (:import [java.nio.file NoSuchFileException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} private-permissions "rw-------")

(defn ^{:stratum 0} posix? [path]
  (try+
    (fs/posix-file-permissions path {:nofollow-links true})
    true
    (catch UnsupportedOperationException _ false)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} existing-permissions [target]
  (try+
    (fs/posix-file-permissions target {:nofollow-links true})
    (catch NoSuchFileException _ private-permissions)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [temporary target]
  (when (posix? temporary)
    (fs/set-posix-file-permissions temporary (existing-permissions target))))

(comment ::checkpoint-permission-policy)
