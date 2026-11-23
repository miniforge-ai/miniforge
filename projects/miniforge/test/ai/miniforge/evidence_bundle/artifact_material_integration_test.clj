;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.artifact-material-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} accepted-preparation-remains-within-the-protected-envelope-budget-test
  (doseq [size [99000 99990]]
    (let [prepared (evidence/prepare-artifact-content (vec (repeat size nil)))]
      (is (or (anomaly/anomaly? prepared)
              (evidence/valid-protected-artifact-content? prepared))))))
