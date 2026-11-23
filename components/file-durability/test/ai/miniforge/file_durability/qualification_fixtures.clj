;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualification-fixtures
  "Substitute deployment probes while retaining the real opaque root binding."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.local-store :as store]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.directory-pin :as pin]
            [ai.miniforge.file-durability.boundary.lock-probe :as locks]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.runtime-peer-fixtures :as peer-runtime]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} profile
  (case (System/getProperty "os.name")
    "Mac OS X" :macos-apfs-internal
    "Linux" :linux-block-posix
    :unsupported))

(def ^{:stratum 0} limits
  "Finite fixture budgets, supplied through the same required host configuration."
  {:qualification/pin-limits {:pin/entry-limit 256 :pin/timeout-ms 3000}
   :qualification/lock-limits peer-runtime/limits})

(def ^{:stratum 0 :private true} native-release! lease/release!)

(defn- ^{:stratum 0} record-pin! [open! issued root limit until]
  (let [held (open! root limit until)]
    (when (satisfies? lease/DirectoryLease held) (swap! issued conj held))
    held))

(defn ^{:stratum 0} candidate [profile directory]
  (let [root (fs/real-path directory)]
    (assoc (identity/read-identity root) :storage/root root :storage/backend profile)))

(defn ^{:stratum 0} peer [root]
  {:peer/executable (fs/file root "intentionally-not-launched")
   :peer/classpath (str root)})

(defn ^{:stratum 0} with-probes! [observe proof f]
  (with-redefs [store/observe observe
                publication/check! (constantly :publication-verified)
                locks/check! proof]
    (f)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} release-issued! [issued]
  (run! native-release! (reverse @issued)))

(defn ^{:stratum 1} qualify-with! [root observe proof]
  (anomaly/let-ok [prepared (durability/prepare-protected-directory! root)]
    (with-probes! observe proof
      #(durability/qualify-local-root! (fs/file prepared) profile (peer prepared) limits))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-lifetimes! [f]
  (let [issued (atom [])]
    (try+
      (with-redefs [pin/open! (partial record-pin! pin/open! issued)] (f))
      (finally (release-issued! issued)))))

(defn ^{:stratum 2} qualify! [root & [proof]]
  (qualify-with! root candidate (or proof (constantly true))))

(comment (peer (fs/file ".")))
