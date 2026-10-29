;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-publication-test
  (:require [ai.miniforge.fsm.interface :as fsm]
            [ai.miniforge.workflow.state-publication :as publication]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} states []
  (let [terminal {:type :final}
        running {:on {:complete :completed}}]
    {:running running
     :completed terminal
     :failed terminal
     :cancelled terminal}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} terminal-machine [status]
  (let [topology (publication/with-refusal-transitions (states))]
    (fsm/define-machine {:fsm/id :terminal-publication
                         :fsm/initial status
                         :fsm/context {}
                         :fsm/states topology})))

(deftest ^{:stratum 1} nonterminal-transitions-and-terminal-metadata-are-preserved
  (let [original (assoc-in (states) [:completed :on :inspect] :completed)
        updated (publication/with-refusal-transitions original)]
    (is (= (:running original) (:running updated)))
    (is (= :final (get-in updated [:completed :type])))
    (is (= :completed (get-in updated [:completed :on :inspect])))
    (is (= updated (publication/with-refusal-transitions updated)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} terminal-refusals-change-the-real-machine-state
  (doseq [status [:completed :failed :cancelled]]
    (let [machine (terminal-machine status)
          initial (fsm/initialize machine)
          refused (fsm/transition-result machine initial publication/refusal-event)]
      (is (fsm/final? machine initial))
      (is (= :failed (fsm/current-state refused)))
      (is (fsm/final? machine refused)))))

(comment ::terminal-state-policy-regression)
