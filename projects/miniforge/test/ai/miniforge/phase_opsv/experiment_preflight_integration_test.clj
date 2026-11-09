;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.experiment-preflight-integration-test
  (:require [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.phase-opsv.experiment-preflight-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-preparation-fixtures :as native]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} native-baseline-preflight-does-not-require-final-actuation-or-candidate-success-test
  (let [input (fixtures/input #(assoc % :experiment-pack/actuation-intent :apply-allowed))
        result (fixtures/evaluate input)
        decision (:opsv/experiment-decision-envelope result)
        contract (get-in input [:request :comparison/pin :comparison/contract])]
    (is (seq (:http-calls input)))
    (is (false? (get-in input [:request :candidate/policy :operational-policy/verification-summary :passed?])))
    (is (envelope/valid? decision))
    (is (= :allow (:envelope/decision decision)))
    (is (= [:instrumentation :environment :blast-radius :abort :evidence-completeness]
           (mapv :gate/id (:opsv/experiment-gate-results result))))
    (is (every? :gate/passed? (:opsv/experiment-gate-results result)))
    (is (= (:comparison/binding contract) (:comparison/binding result)))
    (is (= (get-in input [:request :comparison/pin :comparison/hash]) (:comparison/hash result)))
    (is (not (contains? result :opsv/decision-envelope)))))

(deftest ^{:stratum 0} default-off-and-caller-settings-cannot-enable-trials-test
  (let [input (fixtures/input)
        settings (get-in input [:ctx :execution/opts :opsv/experiment-execution])
        ctx (-> (:ctx input)
                (update :execution/opts dissoc :opsv/experiment-execution)
                (assoc-in [:execution/input :opsv/experiment-execution] settings))
        result (fixtures/evaluate (assoc input :ctx ctx))]
    (is (= :deny (get-in result [:opsv/experiment-decision-envelope :envelope/decision])))))

(deftest ^{:stratum 0} closed-settings-and-request-boundaries-fail-as-data-test
  (let [input (fixtures/input)]
    (doseq [[path value] [[[:ctx :execution/opts :opsv/experiment-execution] false]
                         [[:ctx :execution/opts :opsv/experiment-execution :environment/class] :production]
                         [[:ctx :execution/opts :opsv/experiment-execution :caller-verdict] :allow]
                         [[:ctx :execution/opts :opsv/governance] {}]
                         [[:request :comparison/pin] {}]
                         [[:baseline :environment-fingerprint] {}]]]
      (is (= :invalid-input (:anomaly/type (fixtures/evaluate (assoc-in input path value))))))))

(deftest ^{:stratum 0} each-safety-gate-remains-effective-before-trial-test
  (let [input (fixtures/input)]
    (doseq [[id path value]
            [[:instrumentation [:ctx :execution/opts :opsv/governance :policy/context
                                :opsv/instrumentation-status :cpu :reliable?] false]
             [:environment [:ctx :execution/opts :opsv/governance :policy/context
                            :opsv/allowed-environments] #{}]
             [:blast-radius [:ctx :execution/opts :opsv/governance :policy/context
                             :opsv/blast-radius-limits :max-node-delta] 0]
             [:evidence-completeness [:ctx :execution/input :opsv/metric-snapshot-artifact-refs] []]]]
      (let [result (fixtures/evaluate (assoc-in input path value))
            failed (filterv #(not (:gate/passed? %)) (:opsv/experiment-gate-results result))]
        (is (= [id] (mapv :gate/id failed)))
        (is (= :deny (get-in result [:opsv/experiment-decision-envelope :envelope/decision])))))))

(deftest ^{:stratum 0} original-baseline-and-exact-attempt-payload-bindings-are-required-test
  (let [input (fixtures/input)]
    (is (= :conflict (:anomaly/type (fixtures/evaluate
                                    (assoc-in input [:baseline :verification/id] (random-uuid))))))
    (doseq [request (native/mismatched-requests (:request input))]
      (let [result (fixtures/evaluate (assoc input :request request))]
        (is (not= :allow (get-in result [:opsv/experiment-decision-envelope :envelope/decision])))))
    (doseq [[path value] [[[:ctx :execution/id] (random-uuid)]
                         [[:ctx :execution/input :opsv/experiment-pack :experiment-pack/version] "2.0.0"]
                         [[:ctx :execution/opts :opsv/experiment-execution :experiment/environment] "other"]
                         [[:ctx :execution/opts :opsv/experiment-execution :experiment/declaration
                           :kubernetes/deployment-name] "other"]
                         [[:baseline :environment-fingerprint :config-hash] "changed"]
                         [[:baseline :runtime/target :workload-uid] "replaced"]]]
      (is (= :deny (get-in (fixtures/evaluate (assoc-in input path value))
                           [:opsv/experiment-decision-envelope :envelope/decision]))))))

(deftest ^{:stratum 0} malformed-abort-thresholds-and-undeclared-target-namespaces-deny-test
  (doseq [transform [#(assoc-in % [:experiment-pack/guardrails :abort-thresholds :tail-latency] nil)
                    #(assoc-in % [:experiment-pack/guardrails :blast-radius :namespaces] ["other"])]]
    (let [result (fixtures/evaluate (fixtures/input transform))]
      (is (= :deny (get-in result [:opsv/experiment-decision-envelope :envelope/decision]))))))

(deftest ^{:stratum 0} final-production-allowlist-cannot-enable-temporary-experiments-test
  (let [input (-> (fixtures/input)
                  (assoc-in [:ctx :execution/opts :opsv/governance :policy/context
                             :opsv/production-environments] #{"staging"})
                  (assoc-in [:ctx :execution/opts :opsv/governance :policy/context
                             :opsv/production-allowlist] #{"staging"}))]
    (is (= :deny (get-in (fixtures/evaluate input) [:opsv/experiment-decision-envelope :envelope/decision])))))

(deftest ^{:stratum 0} caller-policy-and-policy-evidence-cannot-replace-runtime-evidence-test
  (let [input (fixtures/input)
        policy (get-in input [:ctx :execution/opts :opsv/governance])
        no-policy (-> input
                      (update-in [:ctx :execution/opts] dissoc :opsv/governance)
                      (assoc-in [:ctx :execution/input :opsv/governance] policy))
        forged (-> input
                   (assoc-in [:ctx :execution/opts :opsv/governance :policy/context :opsv/evidence]
                             {:opsv/metric-snapshot-artifact-refs [(random-uuid)]})
                   (assoc-in [:ctx :execution/input :opsv/metric-snapshot-artifact-refs] []))]
    (doseq [request [no-policy forged]]
      (is (= :deny (get-in (fixtures/evaluate request) [:opsv/experiment-decision-envelope :envelope/decision]))))))

(comment ::temporary-gate-decision-is-never-final-apply-or-provider-permission)
