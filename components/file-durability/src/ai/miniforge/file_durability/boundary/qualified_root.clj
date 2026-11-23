;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.qualified-root
  "Keep an observed root's native inode pinned until its host releases the handle."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]))

;------------------------------------------------------------------------------ Layer 0

(deftype ^{:stratum 0} QualifiedRoot [observation pin]
  lease/DirectoryLease
  (release! [_] (lease/release! pin))
  (live? [_] (lease/live? pin)))

(defn- ^{:stratum 0} revalidate [observation]
  (let [expected (:storage/root observation)
        actual (directory/existing! expected)
        ids (select-keys observation [:storage/device-number :storage/inode])]
    (when (and (= expected actual) (= ids (identity/read-identity actual)))
      (acl/check! actual))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} qualified? [value]
  (instance? QualifiedRoot value))

(defn- ^{:stratum 1} checked-root [value observation]
  (let [root (:storage/root observation)
        actual (revalidate observation)]
    (cond
      (not (lease/live? value)) (boundary/released-root root)
      (nil? actual) (boundary/unqualified-store root (:storage/backend observation))
      :else actual)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-root [^QualifiedRoot value]
  (let [observation (.-observation value)]
    (if (lease/live? value)
      (checked-root value observation)
      (boundary/released-root (:storage/root observation)))))

(comment (qualified? {}))
