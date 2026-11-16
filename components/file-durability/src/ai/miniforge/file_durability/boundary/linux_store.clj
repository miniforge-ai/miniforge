;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-store
  "Check that a supported mount resolves to a non-virtual block device."
  (:require [ai.miniforge.file-durability.boundary.linux-mountinfo :as mountinfo]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} mount-table
  "Use the calling process's mount namespace rather than a host-wide table."
  "/proc/self/mountinfo")

(def ^{:stratum 0 :private true} block-devices
  "The kernel indexes block devices by mountinfo's major:minor value."
  "/sys/dev/block")

(defn- ^{:stratum 0} block-device? [target]
  (and (fs/starts-with? target "/sys/devices")
       (not (fs/starts-with? target "/sys/devices/virtual"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} device-path [{:mount/keys [device]}]
  (let [link (fs/path block-devices device)
        target (when (fs/sym-link? link) (fs/real-path link))]
    (when (and target (block-device? target)) target)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe [root]
  (when-let [entry (mountinfo/matching-mount root (slurp mount-table))]
    (let [backing (device-path entry)]
      (when (and backing (identity/same-device? root (:mount/target entry)))
        (assoc entry :storage/backend :linux-block-posix :storage/device-path backing)))))
