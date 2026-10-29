;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.pr-monitor-registration-test
  "The :pr-monitor phase resolves through the phase registry."
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.interface]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} pr-monitor-is-a-configured-phase-implementation
  ;; The loader config is what a packaged run uses to require phase namespaces.
  (is (some #{'ai.miniforge.phase-software-factory.pr-monitor}
            (phase/configured-phase-namespaces))))

(deftest ^{:stratum 0} pr-monitor-resolves-with-its-defaults
  (let [interceptor (phase/get-phase-interceptor {:phase :pr-monitor})]
    (is (= :ai.miniforge.phase-software-factory.pr-monitor/pr-monitor (:name interceptor)))
    (is (= [] (get-in interceptor [:config :gates])))
    (is (fn? (:leave interceptor)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.pr-monitor-registration-test))
