;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualification-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.local-store :as store]
            [ai.miniforge.file-durability.boundary.lock-probe :as locks]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [ai.miniforge.file-durability.qualification-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reject-probe! [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} verify-attached-boundaries! [root]
  (when (support/posix? root)
    (let [parent (fs/file (fs/real-path root))
          child (fs/file parent "child")
          alias (fs/file parent "alias")
          path (durability/prepare-protected-directory! child)]
      (is (= path (durability/verify-attached-directory parent child)))
      (fs/create-sym-link alias child)
      (is (anomaly/anomaly? (durability/verify-attached-directory parent alias)))
      (with-redefs [identity/same-device? (constantly false)]
        (is (anomaly/anomaly? (durability/verify-attached-directory parent child)))))))

(defn- ^{:stratum 0} verify-peer-proof-required! [root]
  (with-redefs [store/observe fixtures/candidate
                publication/check! (constantly :publication-verified)
                locks/check! (constantly false)]
    (is (anomaly/anomaly? (durability/qualify-local-root! root fixtures/profile (fixtures/peer root))))))

(defn- ^{:stratum 0} verify-forged-handle! [root]
  (is (= :invalid-input (:anomaly/type (durability/resolve-qualified-root (fixtures/peer root))))))

(defn- ^{:stratum 0} verify-peer-paths! [root]
  (doseq [[field unsafe] [[:peer/executable (fs/file "bb")]
                          [:peer/classpath "."]]]
    (is (= :invalid-input
           (:anomaly/type (durability/qualify-local-root!
                           root fixtures/profile (assoc (fixtures/peer root) field unsafe)))))))

(defn- ^{:stratum 0} verify-root-replacement! [parent]
  (when (support/posix? parent)
    (let [root (fs/file parent "qualified")
          previous (fs/path parent "previous")]
      (durability/prepare-protected-directory! root)
      (let [handle (fixtures/qualify! root)]
        (is (= (fs/real-path root) (durability/resolve-qualified-root handle)))
        (fs/move root previous)
        (durability/prepare-protected-directory! root)
        (is (anomaly/anomaly? (durability/resolve-qualified-root handle)))))))

(defn- ^{:stratum 0} verify-access-controls! [root]
  (when (support/posix? root)
    (let [parent (fs/file (fs/real-path root))
          child (fs/file parent "child")
          handle (fixtures/qualify! parent)]
      (durability/prepare-protected-directory! child)
      (with-redefs [acl/macos? (constantly true) probe/read-output (constantly nil)]
        (is (= :unauthorized (:anomaly/type (durability/resolve-qualified-root handle))))
        (is (= :unauthorized (:anomaly/type (durability/verify-attached-directory parent child))))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-alias-before-observation! [root]
  (when (support/posix? root)
    (let [parent (fs/file (fs/real-path root))
          child (fs/file parent "child")
          alias (fs/file parent "alias")]
      (durability/prepare-protected-directory! child)
      (fs/create-sym-link alias child)
      (with-redefs [store/observers {[fixtures/profile (System/getProperty "os.name")] reject-probe!}]
        (is (anomaly/anomaly? (durability/qualify-local-root! alias fixtures/profile (fixtures/peer parent))))))))

(defn- ^{:stratum 1} verify-unsupported-store! [root]
  (with-redefs [publication/check! reject-probe! locks/check! reject-probe!]
    (let [result (durability/qualify-local-root! root :unknown (fixtures/peer root))]
      (is (= :unavailable (:anomaly/type result)))
      (is (empty? (fs/list-dir root))))))

(deftest ^{:stratum 1} test-attached-directory-cannot-cross-store-or-alias-boundaries
  (support/with-root verify-attached-boundaries!))

(deftest ^{:stratum 1} test-observation-and-publication-require-peer-proof
  (support/with-root verify-peer-proof-required!))

(deftest ^{:stratum 1} test-map-cannot-forge-a-qualified-root
  (support/with-root verify-forged-handle!))

(deftest ^{:stratum 1} test-relative-peer-executable-or-classpath-is-rejected
  (support/with-root verify-peer-paths!))

(deftest ^{:stratum 1} test-replaced-protected-root-invalidates-its-handle
  (support/with-root verify-root-replacement!))

(deftest ^{:stratum 1} test-handle-and-child-retain-verifiable-access-controls
  (support/with-root verify-access-controls!))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-storage-alias-is-rejected-before-native-observation
  (support/with-root verify-alias-before-observation!))

(deftest ^{:stratum 2} test-unsupported-store-does-not-start-publication-or-lock-probes
  (support/with-root verify-unsupported-store!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.qualification-test))
