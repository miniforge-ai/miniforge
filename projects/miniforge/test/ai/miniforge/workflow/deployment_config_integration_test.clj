;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.deployment-config-integration-test
  (:require [ai.miniforge.phase-deployment.interface]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} definition []
  {:workflow/id :deployment-config :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :deploy :gates [] :on-fail :done}
                       {:phase :done}]})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-deploy-failure-keeps-phase-identity-through-runner-transition
  ;; No deployment target is supplied: the native configuration boundary refuses
  ;; before infrastructure effects, then the declared recovery phase executes.
  (let [result (support/run-phase-workflow nil (definition))
        phases (:execution/phase-results result)]
    (is (= :completed (:execution/status result)))
    (is (= #{:deploy :done} (set (keys phases))))
    (is (= :failed (get-in phases [:deploy :status])))
    (is (= :error (get-in phases [:deploy :result :status])))
    (is (= :completed (get-in phases [:done :status])))
    (is (empty? (:execution/errors result)))))
