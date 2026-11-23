;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.root-qualification
  "Qualify only the root whose inode stays pinned through observation and proof."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.local-store :as store]
            [ai.miniforge.file-durability.boundary.lock-probe :as locks]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.qualified-root :as root]
            [ai.miniforge.file-durability.boundary.root-pin-admission :as admission]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observed! [directory profile before]
  (anomaly/let-ok [observation (store/observe profile directory)]
    (if (= before (select-keys observation [:storage/device-number :storage/inode]))
      observation
      (boundary/unqualified-store directory profile))))

(defn- ^{:stratum 0} confirmed! [directory profile observation pin]
  (anomaly/let-ok [current (store/observe profile directory)]
    (let [protected (acl/check! (:storage/root current))]
      (if (and (= observation current) (lease/live? pin))
        (root/->QualifiedRoot (assoc current :storage/root protected) pin)
        (boundary/unqualified-store directory profile)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} prove! [directory profile peer limits before pin]
  (anomaly/let-ok [observation (observed! directory profile before)
                  published (publication/check! (:storage/root observation))]
    (if (and (= :publication-verified published)
             (true? (locks/check! (:storage/root observation) peer limits)))
      (confirmed! directory profile observation pin)
      (boundary/unqualified-store directory profile))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} qualify! [directory profile peer limits]
  (if (contains? store/observers [profile (System/getProperty "os.name")])
    (anomaly/let-ok [_ (store/observe profile directory)]
      (admission/call! directory (:qualification/pin-limits limits)
                        (partial prove! directory profile peer (:qualification/lock-limits limits))))
    (boundary/unqualified-store directory profile)))

(comment (keys store/observers))
