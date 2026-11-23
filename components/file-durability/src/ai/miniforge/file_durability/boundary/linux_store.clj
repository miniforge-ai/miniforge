;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-store
  "Check that a supported mount resolves to a non-virtual block device."
  (:require [ai.miniforge.file-durability.boundary.linux-mountinfo :as mountinfo]
            [ai.miniforge.file-durability.boundary.linux-block-device :as block]
            [ai.miniforge.file-durability.boundary.linux-device-number :as device]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} mount-table
  "Use the calling process's mount namespace rather than a host-wide table."
  "/proc/self/mountinfo")

(defn- ^{:stratum 0} bound-mount? [root entry]
  (let [actual (:storage/device-number (identity/read-identity root))
        mounted (:storage/device-number (identity/read-identity (:mount/target entry)))]
    (and (some? actual) (= actual mounted)
         (device/matches? (:mount/device entry) actual))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe [root]
  (let [entry (mountinfo/matching-mount root (slurp mount-table))
        backing (some-> entry :mount/device block/observe)]
    (when (and backing (bound-mount? root entry))
      (assoc entry :storage/backend :linux-block-posix :storage/device-path backing))))

(comment (block/observe "0:0"))
