;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualification-fixtures
  "Substitute deployment probes while retaining the real opaque root binding."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.local-store :as store]
            [ai.miniforge.file-durability.boundary.lock-probe :as locks]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.interface :as durability]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} profile :macos-apfs-internal)

(defn ^{:stratum 0} candidate [profile directory]
  (let [root (fs/real-path directory)]
    (assoc (identity/read-identity root) :storage/root root :storage/backend profile)))

(defn ^{:stratum 0} peer [root]
  {:peer/executable (fs/file root "intentionally-not-launched")
   :peer/classpath (str root)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} qualify! [root]
  (anomaly/let-ok [_ (durability/prepare-protected-directory! root)]
    (with-redefs [store/observe candidate
                  publication/check! (constantly :publication-verified)
                  locks/check! (constantly true)]
      (durability/qualify-local-root! root profile (peer root)))))

(comment (peer (fs/file ".")))
