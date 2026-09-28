;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governance-test
  (:require [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.governance :as governance]
            [ai.miniforge.phase-opsv.governance-fixtures :as fixture]
            [ai.miniforge.phase-opsv.interface :as phase]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} runtime-evaluates-complete-gate-set-without-issuing-authority-test
  (let [output (phase/actuate (fixture/context))
        decision (:opsv/decision-envelope output)]
    (is (envelope/valid? decision))
    (is (= :allow (:envelope/decision decision)))
    (is (= opsv/opsv-gate-ids (mapv :gate/id (:opsv/gate-results output))))
    (is (every? :gate/passed? (:opsv/gate-results output)))
    (is (= "opsv-governance-1.0.0" (get-in decision [:envelope/pins :pins/pack-revision])))
    (is (= 17 (get-in decision [:envelope/pins :pins/event-watermark])))
    (is (= (mapv :gate (get-in output [:opsv/gate-checks :results]))
           (get-in decision [:envelope/pins :pins/rule-ids])))
    (is (= :recommend-only (get-in output [:opsv/actuation-record :effective-actuation-mode])))
    (is (= [] (get-in output [:opsv/actuation-record :governed-effects])))))

(deftest ^{:stratum 0} caller-policy-and-verdicts-do-not-cross-runtime-boundary-test
  (let [ctx (-> (fixture/context)
                (update :execution/opts dissoc :opsv/governance)
                (assoc-in [:execution/input :opsv/governance] fixture/policy))
        output (phase/actuate ctx)]
    (is (= :deny (get-in output [:opsv/decision-envelope :envelope/decision])))
    (is (not-any? #(= :reason/missing-artifact (:reason/code %))
                  (get-in output [:opsv/decision-envelope :envelope/reasons])))
    (is (false? (get-in output [:opsv/gate-checks :passed?])))))

(deftest ^{:stratum 0} malformed-runtime-policy-is-rejected-test
  (doseq [policy [false {} (dissoc fixture/policy :policy/revision)
                   (assoc fixture/policy :policy/revision " ")
                   (assoc fixture/policy :policy/context nil)
                   (assoc fixture/policy :policy/event-watermark nil)
                   (assoc fixture/policy :policy/event-watermark -1)]]
    (is (= :invalid-input
           (:anomaly/type (phase/actuate
                           (assoc-in (fixture/context) [:execution/opts :opsv/governance] policy)))))))

(deftest ^{:stratum 0} runtime-context-cannot-replace-verified-evidence-test
  (let [ctx (-> (fixture/context)
                (assoc-in [:execution/opts :opsv/governance :policy/context :opsv/evidence]
                          fixture/verified)
                (update-in fixture/output-path dissoc :opsv/metric-snapshot-artifact-refs))
        output (phase/actuate ctx)]
    (is (= :deny (get-in output [:opsv/decision-envelope :envelope/decision])))
    (is (false? (:gate/passed? (last (:opsv/gate-results output)))))))

(deftest ^{:stratum 0} each-required-gate-denies-independently-test
  (doseq [[id path value]
          [[:instrumentation [:execution/opts :opsv/governance :policy/context
                               :opsv/instrumentation-status :cpu :reliable?] false]
           [:environment [:execution/opts :opsv/governance :policy/context
                           :opsv/allowed-environments] #{}]
           [:blast-radius [:execution/opts :opsv/governance :policy/context
                            :opsv/blast-radius-limits :max-node-delta] 0]
           [:abort [:execution/input :opsv/experiment-pack :experiment-pack/guardrails
                     :abort-thresholds :tail-latency] nil]
           [:actuation [:execution/opts :opsv/governance :policy/context
                         :opsv/apply-enabled?] false]
           [:evidence-completeness (conj fixture/output-path :opsv/environment-fingerprint) {}]]]
    (let [ctx (-> (fixture/context)
                  (assoc-in [:execution/input :opsv/experiment-pack
                             :experiment-pack/actuation-intent] :apply-allowed)
                  (assoc-in path value))
          output (phase/actuate ctx)
          failed (filterv #(not (:gate/passed? %)) (:opsv/gate-results output))]
      (is (= [id] (mapv :gate/id failed)) (str id))
      (is (= :deny (get-in output [:opsv/decision-envelope :envelope/decision])) (str id))
      (is (= :recommend-only (get-in output [:opsv/actuation-record :effective-actuation-mode]))))))

(deftest ^{:stratum 0} exactly-one-envelope-is-created-per-evaluation-test
  (let [factory envelope/envelope
        created (atom [])]
    (with-redefs [envelope/envelope
                  (fn [reasons obligations pins]
                    (let [decision (factory reasons obligations pins)]
                      (swap! created conj decision)
                      decision))]
      (let [output (phase/actuate (fixture/context))]
        (is (= 1 (count @created)))
        (is (= (first @created) (:opsv/decision-envelope output)))))))

(deftest ^{:stratum 0} missing-policy-and-missing-pack-have-distinct-reasons-test
  (let [context (fixture/context)
        missing-pack (governance/evaluate (update context :execution/input dissoc :opsv/experiment-pack)
                                          fixture/verified)
        missing-policy (governance/evaluate (update context :execution/opts dissoc :opsv/governance)
                                            fixture/verified)]
    (is (some #(= :reason/missing-artifact (:reason/code %))
              (get-in missing-pack [:opsv/decision-envelope :envelope/reasons])))
    (is (not-any? #(= :reason/missing-artifact (:reason/code %))
                  (get-in missing-policy [:opsv/decision-envelope :envelope/reasons])))
    (is (= :deny (get-in missing-policy [:opsv/decision-envelope :envelope/decision])))))

(comment
  (phase/actuate (fixture/context)))
