;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-block-device
  "Resolve a kernel device number only to a non-virtual block-device path."
  (:require [ai.miniforge.file-durability.boundary.linux-device-ancestry :as ancestry]
            [ai.miniforge.file-durability.boundary.linux-device-policy :as policy]
            [ai.miniforge.file-durability.boundary.linux-host :as host]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private block-devices
  "The kernel indexes block devices by mountinfo's major:minor value."
  "/sys/dev/block")

(def ^{:stratum 0} ^:private virtual-devices
  "A virtual block device does not establish the approved persistent backing."
  "/sys/devices/virtual")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe [device]
  (let [link (fs/path block-devices device)
        target (when (fs/sym-link? link) (fs/real-path link))]
    (when (and target (fs/starts-with? target policy/device-tree)
               (not (fs/starts-with? target virtual-devices))
               (host/non-virtual?)
               (policy/eligible? (ancestry/observe target)))
      target)))

(comment (observe "0:0"))
