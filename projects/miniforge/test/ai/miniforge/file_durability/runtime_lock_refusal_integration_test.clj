;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.runtime-lock-refusal-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.directory-policy :as policy]
            [ai.miniforge.file-durability.boundary.lock-probe :as locks]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.native-probe :as native]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [ai.miniforge.file-durability.runtime-peer-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reject-peer! [& _] (throw (AssertionError.)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-refusals! [directory]
  (let [root (fs/file (fs/real-path directory))
        child (fs/file root "child")
        alias (fs/file root "alias")
        peer (fixtures/require-peer!)]
    (is (support/posix? root))
    (durability/prepare-protected-directory! child)
    (fs/create-sym-link alias child)
    (with-redefs [locks/check! reject-peer!]
      (is (anomaly/anomaly? (durability/verify-runtime-locks! alias peer fixtures/limits)))
      (with-redefs [policy/filesystem-profile (constantly :native-defaults)]
        (is (= :unavailable (:anomaly/type (durability/verify-runtime-locks! root peer fixtures/limits)))))
      (with-redefs [acl/macos? (constantly true) native/read-output (constantly nil)]
        (is (= :unauthorized (:anomaly/type (durability/verify-runtime-locks! root peer fixtures/limits))))))
    (is (= #{"child" "alias"} (set (map fs/file-name (fs/list-dir root)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-alias-native-defaults-and-unreadable-acls-never-launch-a-peer
  (support/with-root verify-refusals!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.runtime-lock-refusal-integration-test))
