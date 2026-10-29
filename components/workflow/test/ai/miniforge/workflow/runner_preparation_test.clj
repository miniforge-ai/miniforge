;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-preparation-test
  (:require [ai.miniforge.workflow.runner-preparation :as preparation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pipeline
  [{:name :a :owned-input-keys #{:a/record}} {:name :b}])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-fresh-run-drops-lifecycle-owned-input
  (is (= {:task "x"} (preparation/admit-input pipeline {:task "x" :a/record {:forged true}} {}))))

(deftest ^{:stratum 1} a-resume-keeps-its-checkpointed-input-whole
  (let [input {:task "x" :a/record {:phase :a}}]
    (is (= input (preparation/admit-input pipeline input {:resume-phase-results {:a {}}})))
    (is (= input (preparation/admit-input pipeline input {:resume-machine-snapshot {}})))))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.runner-preparation-test))
