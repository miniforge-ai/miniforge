;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-admission-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.governance-fixtures :as governance]
            [ai.miniforge.phase-opsv.recovery-plan-admission-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.recovery-plan-gate :as gate]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verdict [result] (get-in result [:recovery/plan-decision-envelope :envelope/decision]))

(def ^{:stratum 0 :private true} other-service
  "A second declared pack service, not the retained original Deployment." "catalog-shadow")

(defn- ^{:stratum 0} remediation [action result]
  (let [checks (get-in result [:recovery/plan-gate-checks :results])
        errors (mapcat :errors (concat checks (:recovery/plan-refusals result)))]
    (some #(when (= action (get-in % [:remediation :action])) (:remediation %)) errors)))

(defn- ^{:stratum 0} check-invalid [fixture]
  (doseq [[path value] [[[:ctx :execution/opts :opsv/recovery-plan-admission] false]
                        [[:ctx :execution/opts :opsv/recovery-plan-admission :environment/class] :production]
                        [[:ctx :execution/opts :opsv/recovery-plan-admission :caller-decision] :allow]
                        [[:ctx :execution/opts :opsv/governance] {}]
                        [[:input :recovery/plan-decision-envelope] {:envelope/decision :allow}]]]
    (is (= :invalid-input (:anomaly/type (fixtures/admit (assoc-in fixture path value)))))))

(defn- ^{:stratum 0} check-gate-integrity [{:keys [ctx prepared]}]
  (let [settings (get-in ctx [:execution/opts :opsv/recovery-plan-admission])
        changed (update-in prepared [:recovery/plan :recovery/duration-ms] inc)
        malformed (update prepared :recovery/plan dissoc :recovery/restore)
        digest (hash/content-hash (:recovery/plan malformed))
        rebound (assoc malformed :recovery/hash digest)]
    (is (false? (:passed? (gate/check nil {:opsv/recovery-plan changed :opsv/recovery-admission settings}))))
    (is (false? (:passed? (gate/check nil {:opsv/recovery-plan rebound
                                         :opsv/recovery-admission (assoc settings :recovery/approved-plan-hashes #{digest})}))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-multi-service-declaration [{:keys [ctx input prepared] :as fixture}]
  (let [services (get-in input [:load :request :experiment-pack :experiment-pack/targets :services])
        changed (assoc-in ctx [:execution/opts :opsv/recovery-plan-admission :recovery/declaration
                               :kubernetes/deployment-name] other-service)
        result (fixtures/admit (assoc fixture :ctx changed))]
    (is (contains? (set services) other-service))
    (is (= :allow (verdict (fixtures/admit fixture))))
    (is (= (:recovery/hash prepared) (:recovery/hash result)))
    (is (= :deny (verdict result)))
    (is (= :opsv/recovery-target (get-in result [:recovery/plan-refusals 0 :gate])))))

(defn- ^{:stratum 1} check-remediation [{:keys [ctx] :as fixture}]
  (doseq [[changed action] [[(update ctx :execution/opts dissoc :opsv/recovery-plan-admission)
                            :approve-exact-recovery-plan]
                           [(assoc-in ctx [:execution/opts :opsv/recovery-plan-admission :recovery/environment] "other")
                            :declare-matching-staging-recovery-target]]]
    (let [result (fixtures/admit (assoc fixture :ctx changed))
          guidance (remediation action result)]
      (is (= :deny (verdict result)))
      (is (= action (:action guidance)))
      (is (map? (:details guidance)))
      (is (and (string? (:summary guidance)) (not (.startsWith ^String (:summary guidance) ":recovery/")))))))

(defn- ^{:stratum 1} check-approved [{:keys [ctx input prepared] :as fixture}]
  (let [result (fixtures/admit fixture)
        decision (:recovery/plan-decision-envelope result)]
    (is (= :allow (verdict result)))
    (is (envelope/valid? decision))
    (is (= (:recovery/plan prepared) (:recovery/plan result)))
    (is (= (:recovery/hash prepared) (:recovery/hash result)))
    (is (= (:policy/revision governance/policy) (get-in decision [:envelope/pins :pins/pack-revision])))
    (is (= (:policy/event-watermark governance/policy) (get-in decision [:envelope/pins :pins/event-watermark])))
    (is (= :opsv/recovery-plan-gate (last (get-in decision [:envelope/pins :pins/rule-ids]))))
    (is (= [:instrumentation :environment :blast-radius :abort :evidence-completeness :recovery-plan]
           (mapv :gate/id (:recovery/plan-gate-results result))))
    (is (every? :gate/passed? (:recovery/plan-gate-results result)))
    (is (nil? (get-in ctx [:execution/opts :opsv/experiment-execution])))
    (is (false? (get-in input [:load :request :candidate/policy :operational-policy/verification-summary :passed?])))
    (is (not (contains? result :grant/id)))
    (is (not (contains? result :opsv/phase-produced-artifact-refs)))))

(defn- ^{:stratum 1} check-default-denial [{:keys [ctx input] :as fixture}]
  (let [settings (get-in ctx [:execution/opts :opsv/recovery-plan-admission])
        caller (-> ctx
                   (update :execution/opts dissoc :opsv/recovery-plan-admission)
                   (assoc-in [:execution/input :opsv/recovery-plan-admission] settings))
        forward {:experiment/enabled? true :environment/class :staging :experiment/environment "staging"
                 :experiment/declaration (get-in input [:load :declaration])}]
    (doseq [changed [caller (assoc-in caller [:execution/opts :opsv/experiment-execution] forward)
                     (update ctx :execution/opts dissoc :opsv/governance)]]
      (is (= :deny (verdict (fixtures/admit (assoc fixture :ctx changed))))))))

(defn- ^{:stratum 1} check-safety [fixture]
  (let [fixture (assoc-in fixture [:ctx :execution/opts :opsv/governance :policy/context
                                   :opsv/production-allowlist] #{"staging"})]
    (doseq [[path value] [[[:ctx :execution/opts :opsv/governance :policy/context :opsv/allowed-environments] #{}]
                        [[:ctx :execution/opts :opsv/governance :policy/context :opsv/instrumentation-status :cpu :reliable?] false]
                        [[:ctx :execution/opts :opsv/governance :policy/context :opsv/blast-radius-limits :max-node-delta] 0]
                        [[:ctx :execution/opts :opsv/governance :policy/context :opsv/production-environments] #{"staging"}]
                        [[:ctx :execution/input :opsv/metric-snapshot-artifact-refs] []]
                        [[:ctx :execution/opts :opsv/recovery-plan-admission :recovery/environment] "other"]
                        [[:ctx :execution/opts :opsv/recovery-plan-admission :recovery/declaration :kubernetes/deployment-name] "other"]]]
      (is (= :deny (verdict (fixtures/admit (assoc-in fixture path value))))))))

(defn- ^{:stratum 1} check-hash [fixture]
  (doseq [changed [(assoc-in fixture [:input :load :limits :effect/id] (random-uuid))
                   (assoc-in fixture [:ctx :execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes]
                             #{(hash/content-hash :different-plan)})]]
    (is (= :deny (verdict (fixtures/admit changed))))))

(defn- ^{:stratum 1} check-acknowledgement [{:keys [ctx id] :as fixture}]
  (let [store (:opsv/evidence-assembly-store ctx)
        bundle (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        assembly (evidence/get-opsv-assembly store bundle)]
    (is (= :allow (verdict (fixtures/admit fixture))))
    (swap! store assoc-in [bundle :opsv/artifact-refs] (disj (:opsv/artifact-refs assembly) id))
    (is (anomaly/anomaly? (fixtures/admit fixture)))))

(deftest ^{:stratum 1} invalid-settings-and-caller-verdicts-are-refused-test
  (fixtures/with-input check-invalid))

(deftest ^{:stratum 1} approval-gate-checks-plan-schema-and-recomputes-its-hash-test
  (fixtures/with-input check-gate-integrity))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} independently-approved-bounded-plan-produces-real-pinned-n4-decision-test
  (fixtures/with-input check-approved))

(deftest ^{:stratum 2} forward-opt-in-and-caller-data-do-not-approve-recovery-test
  (fixtures/with-input check-default-denial))

(deftest ^{:stratum 2} staging-target-and-all-existing-safety-gates-remain-required-test
  (fixtures/with-input check-safety))

(deftest ^{:stratum 2} changed-load-or-unapproved-plan-hash-needs-fresh-approval-test
  (fixtures/with-input check-hash))

(deftest ^{:stratum 2} approved-plan-still-requires-current-original-acknowledgement-test
  (fixtures/with-input check-acknowledgement))

(deftest ^{:stratum 2} plan-admission-failures-carry-human-and-machine-remediation-test
  (fixtures/with-input check-remediation))

(deftest ^{:stratum 2} recovery-declaration-cannot-switch-to-another-service-in-the-same-pack-test
  (fixtures/with-input #(update-in % [:experiment-pack/targets :services] conj other-service)
                       check-multi-service-declaration))

(comment ::no-fake-retention-grant-or-provider-execution)
