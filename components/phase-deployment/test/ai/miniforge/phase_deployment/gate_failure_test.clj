;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.gate-failure-test
  "Deployment :leave handlers act only on a phase whose gates passed."
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase-deployment.deploy :as deploy]
            [ai.miniforge.phase-deployment.deploy-result :as deploy-result]
            [ai.miniforge.phase-deployment.interface]
            [ai.miniforge.phase-deployment.provision :as provision]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.gate.interface :as gate]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gate-failures
  "The two projections the workflow's gate validation puts on a phase map."
  {:denied {:phase/status :failed}
   :unacknowledged {:status :failed :phase/status :failed
                    :phase/decision-unacknowledged {:anomaly/type :unavailable}}})

(defn- ^{:stratum 0} command [parsed]
  {:success? true :stdout "" :stderr "" :exit-code 0 :duration-ms 1
   :command "pulumi" :parsed parsed})

(defn- ^{:stratum 0} previewed [gate-failure]
  {:phase (merge {:name :provision :status :pending-gates :started-at 0
                  :result {:status :preview-complete :output {:steps []}}
                  :provision-config {:stack-dir "/tmp/stack" :stack "prod"}}
                 gate-failure)
   :execution/metrics {}})

(defn- ^{:stratum 0} deployed [pod-state]
  (deploy-result/store-deployment {} 0 (log/create-logger {:min-level :error})
                                  {:phase-config {:gates [:deploy-healthy]}}
                                  {:deploy/status :success :deploy/stage :observe
                                   :deploy/pod-state pod-state}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} leave-provision [ctx]
  (let [applied (atom 0)]
    (with-redefs [shell/pulumi-up! (fn [& _] (swap! applied inc) (command {}))
                  shell/pulumi-outputs! (constantly (command {:url "https://x"}))]
      [(provision/leave-provision ctx) @applied])))

(deftest ^{:stratum 1} leave-deploy-fails-a-gate-failed-deploy
  (doseq [[failure projection] gate-failures]
    (testing (str failure " gate decision")
      (is (= :failed (get-in (deploy/leave-deploy {:phase (merge {:status :completed} projection)})
                             [:phase :status]))))))

(deftest ^{:stratum 1} deploy-healthy-gate-reads-the-deploy-output
  (let [healthy {:pod-count 2 :ready-count 2 :pods [{:ready? true} {:ready? true}]}
        unready {:pod-count 2 :ready-count 1 :pods [{:ready? true} {:ready? false}]}
        output (fn [pod-state] (get-in (deployed pod-state) [:phase :result :output]))]
    (is (:passed? (gate/check-gates [:deploy-healthy] (output healthy) {})))
    (is (not (:passed? (gate/check-gates [:deploy-healthy] (output unready) {}))))
    (is (= healthy (:pod-state (output healthy))) "the output still carries :pod-state")))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} leave-provision-never-applies-a-gate-failed-preview
  (let [[out applied] (leave-provision (previewed nil))]
    (is (= 1 applied) "precondition: a preview whose gates passed is applied")
    (is (= :completed (get-in out [:phase :status]))))
  (doseq [[failure projection] gate-failures]
    (testing (str failure " gate decision")
      (let [[out applied] (leave-provision (previewed projection))]
        (is (zero? applied) "pulumi up never runs")
        (is (= :failed (get-in out [:phase :status])))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-deployment.gate-failure-test))
