;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.native-agent-test
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.registry]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} native-opsv-phases-declare-agentless-execution
  (doseq [phase-key opsv/phase-keys]
    (let [interceptor (phase/get-phase-interceptor {:phase phase-key})
          config (:config interceptor)]
      (is (nil? (:agent config)))
      (is (= :agent/none (:phase/expected-agent config))))))
