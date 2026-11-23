;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.qualified-root
  "Run qualification from trusted host configuration; bind evidence to one root."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.local-store :as store]
            [ai.miniforge.file-durability.boundary.lock-probe :as lock]
            [ai.miniforge.file-durability.boundary.macos-acl :as macos-acl]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]))

;------------------------------------------------------------------------------ Layer 0

(deftype ^{:stratum 0 :private true} QualifiedRoot [observation])

(defn- ^{:stratum 0} revalidate [observation]
  (let [expected (:storage/root observation)
        actual (directory/existing! expected)
        ids (select-keys observation [:storage/device-number :storage/inode])]
    (when (and (= expected actual) (= ids (identity/read-identity actual)))
      (macos-acl/check! actual))))

(defn- ^{:stratum 0} confirmed-root [root profile observation]
  (anomaly/let-ok [current (store/observe profile root)]
    (if (= observation current)
      (let [protected (macos-acl/check! (:storage/root current))]
        (QualifiedRoot. (assoc current :storage/root protected)))
      (boundary/unqualified-store root profile))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} qualified? [value]
  (instance? QualifiedRoot value))

(defn ^{:stratum 1} resolve-root [^QualifiedRoot value]
  (let [observation (.-observation value)
        root (:storage/root observation)]
    (or (revalidate observation)
        (boundary/unqualified-store root (:storage/backend observation)))))

(defn ^{:stratum 1} qualify! [root profile peer]
  (anomaly/let-ok [observation (store/observe profile root)
                  published (publication/check! (:storage/root observation))]
    (if (and (= :publication-verified published) (lock/check! (:storage/root observation) peer))
      (confirmed-root root profile observation)
      (boundary/unqualified-store root profile))))

(comment (qualified? {}))
