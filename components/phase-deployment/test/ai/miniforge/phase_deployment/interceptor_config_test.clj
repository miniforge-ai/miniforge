;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.interceptor-config-test
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-deployment.deploy :as deploy]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-entries
  {:deploy #'deploy/enter-deploy})

(defn- ^{:stratum 0} configuration [kind]
  {:phase kind :on-fail :done :on-success :done
   :gates [:deployment-test-gate] :budget {:tokens 0 :iterations 1}
   :deployment-test/marker :configured})

(defn- ^{:stratum 0} enter-context [ctx] ctx)

(defn- ^{:stratum 0} verify-configured-enter [config]
  (let [interceptor (phase/get-phase-interceptor config)
        merged (phase/merge-with-defaults config)
        initial {:phase-config {:deployment-test/marker :stale} :execution/input {:kept true}}
        entered ((:enter interceptor) initial)]
    (is (= merged (:config interceptor)))
    (is (= merged (:phase-config entered)))
    (is (= (:execution/input initial) (:execution/input entered)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} configured-deployment-phases-bind-their-own-effective-config
  (doseq [[kind entry] phase-entries]
    (with-redefs-fn {entry enter-context}
      (partial verify-configured-enter (configuration kind)))))

(deftest ^{:stratum 1} deployment-defaults-retain-phase-identity-and-default-gates
  (doseq [kind (keys phase-entries)]
    (let [config {:phase kind}
          interceptor (phase/get-phase-interceptor config)]
      (is (= (phase/merge-with-defaults config) (:config interceptor)))
      (is (= kind (get-in interceptor [:config :phase]))))))
