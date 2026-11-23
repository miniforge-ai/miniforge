;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-permissions
  "POSIX publication policy; other providers retain their native defaults."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]])
  (:import [java.io IOException]
           [java.nio.file NoSuchFileException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} private-permissions
  "First checkpoint writes are owner-only; replacements retain existing sharing."
  "rw-------")

(defn ^{:stratum 0} posix? [path]
  (let [profile (durability/filesystem-profile (fs/file path))]
    (if (anomaly/anomaly? profile)
      (throw (IOException. ^String (:anomaly/message profile)))
      (= :posix profile))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} existing-permissions [target]
  (try+
    (let [attributes (fs/read-attributes target "posix:*" {:nofollow-links true})]
      (if (:isSymbolicLink attributes) private-permissions (:permissions attributes)))
    (catch NoSuchFileException _ private-permissions)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [temporary target]
  (when (posix? temporary)
    (fs/set-posix-file-permissions temporary (existing-permissions target))))

(comment ::checkpoint-permission-policy)
