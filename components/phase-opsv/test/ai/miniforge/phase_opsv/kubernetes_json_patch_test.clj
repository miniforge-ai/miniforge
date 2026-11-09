;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-json-patch-test
  (:require [ai.miniforge.phase-opsv.kubernetes-json-patch :as patch]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} remove-does-not-carry-a-value-or-change-the-path-test
  (is (= {"op" "remove"
          "path" "/spec/schedulingGates"}
          (patch/remove-value "/spec/schedulingGates"))))

(deftest ^{:stratum 0} valued-operations-preserve-false-nil-and-exact-paths-test
  (doseq [value [false nil 0 ["unchanged"]]]
    (is (= {"op" "test"
            "path" "/test"
            "value" value} (patch/test-value "/test" value)))
    (is (= {"op" "replace"
            "path" "/test"
            "value" value} (patch/replace-value "/test" value)))))

(deftest ^{:stratum 0} ownership-preconditions-preserve-uid-then-current-revision-test
  (is (= [{"op" "test"
           "path" "/metadata/uid"
           "value" "owned-uid"}
          {"op" "test"
           "path" "/metadata/resourceVersion"
           "value" "fresh-revision"}]
          (patch/metadata-tests {"uid" "owned-uid"
                                 "resourceVersion" "fresh-revision"}))))

(comment
  (patch/remove-value "/spec/schedulingGates"))
