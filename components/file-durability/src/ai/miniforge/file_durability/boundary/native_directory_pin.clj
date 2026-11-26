;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.native-directory-pin
  "Descriptor identity and lifetime are one owned native capability."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.descriptor-process :as process]
            [ai.miniforge.file-durability.boundary.descriptor-receipt :as receipt]
            [ai.miniforge.file-durability.boundary.descriptor-lease :as native-lease]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.owned-resource :as owned]
            [ai.miniforge.file-durability.boundary.probe-deadline :as deadline]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} bind! [path until shutdown-ms child]
  (let [identity (receipt/receive! until child)]
    (if (and identity (deadline/live? until) (.isAlive ^Process child))
      (native-lease/->DescriptorPin path child identity (atom false) shutdown-ms)
      (boundary/unqualified-store path :storage/directory-descriptor))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} open! [path until shutdown-ms]
  (if (deadline/live? until)
    (owned/call-with-release! (process/start! path)
                             (partial process/release! path shutdown-ms)
                             (partial bind! path until shutdown-ms))
    (boundary/unqualified-store path :storage/directory-descriptor)))

(comment (satisfies? lease/DescriptorIdentity nil))
