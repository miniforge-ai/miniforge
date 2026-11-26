;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.descriptor-lease
  "A descriptor's identity is usable only during its owned native lifetime."
  (:require [ai.miniforge.file-durability.boundary.descriptor-process :as process]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} active? [^Process child closed]
  (and (not @closed) (.isAlive child)))

;------------------------------------------------------------------------------ Layer 1

(deftype ^{:stratum 1} DescriptorPin [path child identity closed shutdown-ms]
  lease/DirectoryLease
  (release! [_]
    (reset! closed true)
    (process/release! path shutdown-ms child))
  (live? [_] (active? child closed))
  lease/DescriptorIdentity
  (identity-of [_] (when (active? child closed) identity)))

(comment (satisfies? lease/DescriptorIdentity nil))
