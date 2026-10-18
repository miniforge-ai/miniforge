;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.dag-schema-test
  (:require [ai.miniforge.evidence-bundle.dag-fixtures :as fixtures]
            [ai.miniforge.evidence-bundle.schema.dag-evidence :as dag]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} dag-records-enforce-required-fields
  (doseq [[validate construct] [[dag/run? fixtures/run] [dag/merge? fixtures/merge-evidence]]]
    (let [value (construct)]
      (is (validate value))
      (doseq [field (keys value)]
        (is (not (validate (dissoc value field)))))))
  (let [task (fixtures/task)]
    (is (dag/tasks? [task]))
    (is (dag/tasks? []))
    (doseq [field (keys task)]
      (is (not (dag/tasks? [(dissoc task field)]))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.dag-schema-test))
