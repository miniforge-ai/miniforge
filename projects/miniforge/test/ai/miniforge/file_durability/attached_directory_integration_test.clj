;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.attached-directory-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.directory-pin-fixtures :as fixtures]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.owned-resource-fixtures :as resources]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-child-binding! [directory]
  (let [parent (fs/file (fs/real-path directory))
        child (fs/file parent "child")
        alias (fs/file parent "alias")
        nested (fs/file child "nested")
        path (fixtures/prepare! child)]
    (fixtures/prepare! nested)
    (is (= path (durability/verify-attached-directory parent child)))
    (fs/create-sym-link alias child)
    (is (anomaly/anomaly? (durability/verify-attached-directory parent alias)))
    (is (anomaly/anomaly? (durability/verify-attached-directory parent nested)))
    (with-redefs [identity/read-identity (constantly nil)]
      (is (anomaly/anomaly? (durability/verify-attached-directory parent child))))
    (with-redefs [acl/macos? (constantly true) probe/read-output (constantly nil)]
      (is (= :unauthorized (:anomaly/type (durability/verify-attached-directory parent child)))))))

(deftest ^{:stratum 0} test-invalid-child-arguments-refuse-without-native-io
  (with-redefs [probe/read-output resources/reject-io!]
    (is (= :invalid-input (:anomaly/type (durability/verify-attached-directory nil nil))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} test-canonical-child-cannot-cross-parent-device-alias-or-acl-boundaries
  (support/with-root verify-child-binding!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.attached-directory-integration-test))
