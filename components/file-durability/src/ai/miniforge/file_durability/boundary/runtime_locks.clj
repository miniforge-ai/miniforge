;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.runtime-locks
  "Protect a canonical POSIX probe root before starting the peer handshake."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-policy :as policy]
            [ai.miniforge.file-durability.boundary.lock-probe :as probe]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prove! [^File root peer limits]
  (let [path (directory/existing! root)]
    (if (and (= path (.toPath root))
             (= :posix (policy/filesystem-profile path))
             (= path (acl/check! path))
             (probe/check! path peer limits))
      :runtime-locks-verified
      (boundary/unverified-locks root))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} verify! [root peer limits]
  (if (.isInterrupted (Thread/currentThread))
    (boundary/unverified-locks root)
    (prove! root peer limits)))

(comment
  (try
    (.interrupt (Thread/currentThread))
    (verify! nil nil nil)
    (finally (Thread/interrupted))))
