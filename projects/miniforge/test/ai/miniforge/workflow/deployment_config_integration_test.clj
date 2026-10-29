;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.deployment-config-integration-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-deployment.interface]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} definition []
  {:workflow/id :deployment-config :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :deploy :gates [] :on-fail :done}
                       {:phase :done}]})

(defn- ^{:stratum 0} command [parsed]
  {:success? true :stdout "" :stderr "" :exit-code 0 :duration-ms 1
   :command "pulumi" :parsed parsed})

(defn- ^{:stratum 0} provision-context [stream]
  {:execution/id (random-uuid)
   :event-stream stream
   :execution/current-phase :provision
   :execution/input {:stack-dir "/tmp/stack" :stack "prod"}
   :execution/metrics {} :execution/errors []
   :execution/response-chain (response/create :deployment-gates)})

(defn- ^{:stratum 0} decision-rejecting-publish [publish stream event]
  (if (= :gate/decision (:event/type event))
    {:rejected? true :reason :fixture/decision-refused}
    (publish stream event)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} provision
  "Run the real provision interceptor through the real lifecycle and gates
   with a fixture preview. Returns [phase-result pulumi-up-calls]."
  [preview ctx]
  (let [applied (atom 0)]
    (with-redefs [shell/pulumi-preview! (constantly (command preview))
                  shell/pulumi-up! (fn [& _] (swap! applied inc) (command {}))
                  shell/pulumi-outputs! (constantly (command {:url "https://x"}))]
      (let [[_ result] (lifecycle/execute-phase-lifecycle
                        (phase/get-phase-interceptor {:phase :provision}) ctx)]
        [result @applied]))))

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

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} provision-applies-only-a-preview-its-gates-passed
  (testing "a preview with changes passes the registered deployment gates and is applied"
    (let [[result applied] (provision {:steps [{:op "create" :type "gcp:storage/bucket:Bucket"}]}
                                      (provision-context (events/create-event-stream {:sinks []})))]
      (is (= :allow (get-in result [:phase/decision-envelope :envelope/decision])))
      (is (= :completed (:status result)))
      (is (= 1 applied))))
  (testing "an empty preview fails :provision-validated: the denial is never applied"
    (let [[result applied] (provision {} (provision-context (events/create-event-stream {:sinks []})))]
      (is (= :deny (get-in result [:phase/decision-envelope :envelope/decision])))
      (is (= :failed (:status result)))
      (is (zero? applied))))
  (testing "a gate decision the stream rejects is never applied"
    (let [[result applied] (with-redefs [events/publish! (partial decision-rejecting-publish events/publish!)]
                             (provision {:steps [{:op "create" :type "gcp:storage/bucket:Bucket"}]}
                                        (provision-context (events/create-event-stream {:sinks []}))))]
      (is (contains? result :phase/decision-unacknowledged))
      (is (= :failed (:status result)))
      (is (zero? applied)))))
