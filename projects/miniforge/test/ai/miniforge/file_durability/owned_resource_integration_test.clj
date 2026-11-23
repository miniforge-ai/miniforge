;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.owned-resource-integration-test
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.owned-resource :as owned]
            [ai.miniforge.file-durability.owned-resource-fixtures :as fixtures]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]
           [java.nio.channels FileChannel]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-refused! [action ^FileChannel resource]
  (try (owned/call! resource action) (catch IOException _ nil))
  (is (not (.isOpen resource))))

(defn- ^{:stratum 0} verify-retained! [^FileChannel resource]
  (is (= :retained (owned/call! resource (constantly :retained))))
  (is (.isOpen resource))
  resource)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} test-refused-or-throwing-owner-closes-its-resource
  (doseq [action [(constantly (boundary/unqualified-store nil :storage/directory-pin)) fixtures/reject-io!]]
    (fixtures/with-resource (partial verify-refused! action))))

(deftest ^{:stratum 1 :integration true} test-success-transfers-resource-to-the-caller
  (let [^FileChannel resource (fixtures/with-resource verify-retained!)]
    (is (not (.isOpen resource)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.owned-resource-integration-test))
