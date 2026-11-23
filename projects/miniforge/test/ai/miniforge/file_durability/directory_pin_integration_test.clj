;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.directory-pin-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.directory-pin-fixtures :as fixtures]
            [ai.miniforge.file-durability.owned-resource-fixtures :as resources]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.channels FileChannel]
           [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} open-stream
  (ns-resolve 'ai.miniforge.file-durability.boundary.directory-pin 'open-stream!))

(def ^{:stratum 0 :private true} create-witness
  (ns-resolve 'ai.miniforge.file-durability.boundary.directory-pin-witness 'create!))

(def ^{:stratum 0 :private true} remove-witness
  (ns-resolve 'ai.miniforge.file-durability.boundary.directory-pin-witness 'remove!))

(defn- ^{:stratum 0} verify-live-pin! [root]
  (let [path (fs/real-path root)
        held (fixtures/open! root)]
    (try
      (is (lease/live? held))
      (is (empty? (fs/list-dir path)))
      (lease/release! held)
      (is (false? (lease/live? held)))
      (finally (fixtures/release! held)))))

(defn- ^{:stratum 0} verify-unlinked-pin! [parent]
  (let [root (fixtures/prepare! (fs/path parent "root"))
        original (identity/read-identity root)
        held (fixtures/open! root)]
    (try
      (Files/delete root)
      (fixtures/prepare! root)
      (is (lease/live? held))
      (is (not= original (identity/read-identity root)))
      (finally (fixtures/release! held)))))

(defn- ^{:stratum 0} verify-refusal! [root]
  (let [result (fixtures/open! root)]
    (try
      (is (anomaly/anomaly? result))
      (finally
        (when (satisfies? lease/DirectoryLease result) (fixtures/release! result))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-unknown-resource! [root resource]
  (with-redefs-fn {open-stream (constantly resource)}
    #(verify-refusal! root))
  (is (not (.isOpen ^FileChannel resource)))
  (is (empty? (fs/list-dir root))))

(defn- ^{:stratum 1} verify-replacement-before-binding! [parent]
  (let [root (fixtures/prepare! (fs/path parent "root"))
        witness (volatile! nil)
        create! (comp (partial vreset! witness) @create-witness)
        start! (partial fixtures/replace-before-open! witness @open-stream)]
    (with-redefs-fn {create-witness create!
                    open-stream start!}
      #(verify-refusal! root))
    (is (empty? (fs/list-dir root)))))

(defn- ^{:stratum 1} verify-acquisition-io-failure! [root]
  (with-redefs-fn {open-stream resources/reject-io!}
    #(is (thrown? IOException (fixtures/open! root))))
  (is (empty? (fs/list-dir root))))

(defn- ^{:stratum 1} verify-cleanup-io-failure! [root]
  (let [stream (volatile! nil)
        witness (volatile! nil)
        start! (comp (partial vreset! stream) @open-stream)
        remove! (comp resources/reject-io! (partial vreset! witness))]
    (try
      (with-redefs-fn {open-stream start!
                      remove-witness remove!}
        #(is (thrown? IOException (fixtures/open! root))))
      (is (thrown-with-msg? IllegalStateException #"closed" (.iterator ^Iterable @stream)))
      (is (fs/exists? @witness))
      (finally (@remove-witness @witness)))
    (is (empty? (fs/list-dir root)))))

(deftest ^{:stratum 1 :integration true} test-pin-owns-a-clean-directory-until-idempotent-close
  (support/with-root verify-live-pin!))

(deftest ^{:stratum 1 :integration true} test-open-descriptor-keeps-unlinked-inode-distinct-from-replacement
  (support/with-root verify-unlinked-pin!))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-unknown-native-provider-refuses-closes-and-cleans-its-witness
  (resources/with-root-and-resource verify-unknown-resource!))

(deftest ^{:stratum 2 :integration true} test-replacement-path-cannot-bind-the-original-directory-descriptor
  (support/with-root verify-replacement-before-binding!))

(deftest ^{:stratum 2 :integration true} test-native-acquisition-failure-removes-its-witness
  (support/with-root verify-acquisition-io-failure!))

(deftest ^{:stratum 2 :integration true} test-cleanup-io-failure-closes-the-acquired-native-stream
  (support/with-root verify-cleanup-io-failure!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.directory-pin-integration-test))
