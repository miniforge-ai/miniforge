;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualified-root-lifetime-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.directory-pin :as pin]
            [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.local-store :as store]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [ai.miniforge.file-durability.qualification-fixtures :as fixtures]
            [ai.miniforge.file-durability.qualification-race-fixtures :as race]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each fixtures/with-lifetimes!)

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-refusal-closes! [proof root]
  (let [held (volatile! nil)
        result (race/with-pin! held #(fixtures/qualify! root (partial proof held)))]
    (is (anomaly/anomaly? result))
    (is (satisfies? lease/DirectoryLease @held))
    (is (false? (lease/live? @held)))))

(defn- ^{:stratum 0} verify-pin-precedes-first-observation! [root]
  (let [held (volatile! nil)
        observe (partial race/replace-observation! held (atom false))
        result (race/with-pin! held #(fixtures/qualify-with! root observe (constantly true)))]
    (is (anomaly/anomaly? result))
    (is (satisfies? lease/DirectoryLease @held))
    (is (false? (lease/live? @held)))))

(defn- ^{:stratum 0} verify-handle-owns-unlinked-inode! [parent]
  (let [root (fs/file parent "root")
        handle (fixtures/qualify! root)
        path (durability/resolve-qualified-root handle)
        original (identity/read-identity path)
        reads (atom 0)]
    (is (lease/live? handle))
    (race/replace! path)
    (is (not= original (identity/read-identity path)))
    (is (anomaly/anomaly? (durability/resolve-qualified-root handle)))
    (is (= :qualified-root-released (durability/release-qualified-root! handle)))
    (is (= :qualified-root-released (durability/release-qualified-root! handle)))
    (with-redefs [identity/read-identity (partial race/count-identity! reads original)]
      (is (= :unavailable (:anomaly/type (durability/resolve-qualified-root handle)))))
    (is (zero? @reads))))

(defn- ^{:stratum 0} verify-closed-handle-cannot-reuse-advertised-identity! [root]
  (let [handle (fixtures/qualify! root)
        path (durability/resolve-qualified-root handle)
        original (identity/read-identity path)]
    (durability/release-qualified-root! handle)
    (race/replace! path)
    (with-redefs [identity/read-identity (constantly original)]
      (is (= :unavailable (:anomaly/type (durability/resolve-qualified-root handle)))))
    (is (= :invalid-input (:anomaly/type (durability/release-qualified-root! {}))))))

(defn- ^{:stratum 0} verify-invalid-limits! [root]
  (with-redefs [pin/open! race/reject-io!]
    (doseq [limits [nil {}
                    (update fixtures/limits :qualification/pin-limits dissoc :pin/entry-limit)
                    (assoc-in fixtures/limits [:qualification/pin-limits :pin/entry-limit] 0)
                    (assoc-in fixtures/limits [:qualification/pin-limits :pin/timeout-ms] (inc Integer/MAX_VALUE))
                    (assoc-in fixtures/limits [:qualification/pin-limits :pin/timeout-ms] Double/NaN)
                    (dissoc fixtures/limits :qualification/lock-limits)
                    (assoc-in fixtures/limits [:qualification/lock-limits :lock/timeout-ms] 0)
                    (assoc fixtures/limits :unknown/limit 1)]]
      (is (= :invalid-input
             (:anomaly/type (durability/qualify-local-root! root fixtures/profile (fixtures/peer root) limits)))))))

(defn- ^{:stratum 0} verify-wrong-os-before-pin! [root]
  (let [other (if (= :macos-apfs-internal fixtures/profile) :linux-block-posix :macos-apfs-internal)]
    (with-redefs [pin/open! race/reject-io! publication/check! race/reject-io!]
      (is (= :unavailable
             (:anomaly/type (durability/qualify-local-root! root other (fixtures/peer root) fixtures/limits)))))
    (is (empty? (fs/list-dir root)))))

(defn- ^{:stratum 0} verify-unsupported-store-before-pin! [root]
  (let [refused (boundary/unqualified-store root fixtures/profile)]
    (with-redefs [store/observe (constantly refused)
                  pin/open! race/reject-io!
                  publication/check! race/reject-io!]
      (is (identical? refused
                      (durability/qualify-local-root! root fixtures/profile (fixtures/peer root) fixtures/limits))))
    (is (empty? (fs/list-dir root)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} test-issued-handle-pins-an-unlinked-inode-until-explicit-release
  (support/with-root verify-handle-owns-unlinked-inode!))

(deftest ^{:stratum 1 :integration true} test-released-handle-cannot-resolve-even-if-identity-values-are-reused
  (support/with-root verify-closed-handle-cannot-reuse-advertised-identity!))

(deftest ^{:stratum 1 :integration true} test-replacement-between-pin-and-first-observation-refuses-and-closes
  (support/with-root verify-pin-precedes-first-observation!))

(deftest ^{:stratum 1 :integration true} test-failed-peer-proof-releases-the-acquired-pin
  (support/with-root (partial verify-refusal-closes! race/fail-proof!)))

(deftest ^{:stratum 1 :integration true} test-thrown-peer-proof-releases-the-acquired-pin
  (support/with-root (partial verify-refusal-closes! race/throw-proof!)))

(deftest ^{:stratum 1 :integration true} test-closing-during-proof-cannot-issue-a-qualified-root
  (support/with-root (partial verify-refusal-closes! race/close-before-confirmation!)))

(deftest ^{:stratum 1 :integration true} test-invalid-or-missing-host-bounds-refuse-before-acquiring-a-pin
  (support/with-root verify-invalid-limits!))

(deftest ^{:stratum 1 :integration true} test-known-profile-for-the-wrong-os-refuses-before-witness-creation
  (support/with-root verify-wrong-os-before-pin!))

(deftest ^{:stratum 1 :integration true} test-unsupported-store-refuses-before-any-pin-witness
  (support/with-root verify-unsupported-store-before-pin!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.qualified-root-lifetime-integration-test))
