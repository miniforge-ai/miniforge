;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualification-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.local-store :as store]
            [ai.miniforge.file-durability.boundary.lock-probe :as locks]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} profile :macos-apfs-internal)

(defn- ^{:stratum 0} candidate [profile directory]
  (let [root (fs/real-path directory)]
    (assoc (identity/read-identity root) :storage/root root :storage/backend profile)))

(defn- ^{:stratum 0} peer [root]
  {:peer/executable (fs/file root "intentionally-not-launched")
   :peer/classpath (str root)})

(defn- ^{:stratum 0} reject-probe! [& _] (throw (AssertionError.)))

(deftest ^{:stratum 0} an-attached-directory-cannot-cross-a-store-or-alias-boundary
  (support/with-root
   (fn [root]
     (when (support/posix? root)
       (let [parent (fs/file (fs/real-path root))
             child (fs/file parent "child")
             alias (fs/file parent "alias")
             path (durability/prepare-protected-directory! child)]
         (is (= path (durability/verify-attached-directory parent child)))
         (fs/create-sym-link alias child)
         (is (anomaly/anomaly? (durability/verify-attached-directory parent alias)))
         (with-redefs [identity/same-device? (constantly false)]
           (is (anomaly/anomaly? (durability/verify-attached-directory parent child)))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unsupported-store-does-not-start-a-publication-or-lock-probe
  (support/with-root
   (fn [root]
     (with-redefs [publication/check! reject-probe! locks/check! reject-probe!]
       (let [result (durability/qualify-local-root! root :unknown (peer root))]
         (is (= :unavailable (:anomaly/type result)))
         (is (empty? (fs/list-dir root))))))))

(deftest ^{:stratum 1} an-observation-and-publication-without-peer-proof-are-not-qualification
  (support/with-root
   (fn [root]
     (with-redefs [store/observe candidate
                   publication/check! (constantly :publication-verified)
                   locks/check! (constantly false)]
       (is (anomaly/anomaly? (durability/qualify-local-root! root profile (peer root))))))))

(deftest ^{:stratum 1} a-map-cannot-be-used-in-place-of-a-qualified-root
  (support/with-root
   (fn [root]
     (is (= :invalid-input (:anomaly/type (durability/resolve-qualified-root (peer root))))))))

(deftest ^{:stratum 1} relative-executable-or-classpath-is-rejected
  (support/with-root
   (fn [root]
     (doseq [[field unsafe] [[:peer/executable (fs/file "bb")]
                             [:peer/classpath "."]]]
       (is (= :invalid-input
              (:anomaly/type (durability/qualify-local-root! root profile (assoc (peer root) field unsafe)))))))))

(deftest ^{:stratum 1} replacing-a-protected-root-invalidates-its-handle
  (support/with-root
   (fn [parent]
     (when (support/posix? parent)
       (let [root (fs/file parent "qualified")
             previous (fs/path parent "previous")]
         (durability/prepare-protected-directory! root)
         (with-redefs [store/observe candidate
                       publication/check! (constantly :publication-verified)
                       locks/check! (constantly true)]
           (let [handle (durability/qualify-local-root! root profile (peer parent))]
             (is (= (fs/real-path root) (durability/resolve-qualified-root handle)))
             (fs/move root previous)
             (durability/prepare-protected-directory! root)
             (is (anomaly/anomaly? (durability/resolve-qualified-root handle))))))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.qualification-test))
