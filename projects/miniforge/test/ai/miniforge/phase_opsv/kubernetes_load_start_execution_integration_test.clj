;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-execution-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-start-execution-fixtures :as fixtures]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} claimed-provider-pod-read
  "Readiness, constructor, readiness, then claimed native reconstruction each read the owned Pod." 4)

(def ^{:stratum 0 :private true} beyond-grant-lifetime-seconds
  "Advance past the issuer's thirty-minute lifetime without sleeping." 1801)

(defn- ^{:stratum 0} check-success [{:keys [host/runtime] :as fixture}]
  (let [[result calls] (fixtures/execute fixture)
        patch (first (fixtures/mutations calls))
        audit (fixtures/dispositions fixture)
        assembly (fixtures/assembly fixture)]
    (is (= :succeeded (:effect/state result)))
    (is (= :granted (:effect/authority result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id result))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= :patch (:method (last calls))))
    (is (every? #(= :get (:method %)) (butlast calls)))
    (is (= (get-in result [:effect/proposal :kubernetes/request :body]) (json/parse-string (:body patch))))
    (is (true? (get-in result [:effect/observed :load/gate-release-acknowledged?])))
    (is (= [:proposed :committing :succeeded] (mapv :opsv/effect-state audit)))
    (is (every? (:opsv/event-refs assembly) (map :event/id audit)))
    (is (contains? (:opsv/grant-refs assembly) (:effect/grant-id result)))
    (is (not-any? #(contains? (:effect/observed result) %) [:load/started? :slo/passed? :recovery/verified?]))))

(defn- ^{:stratum 0} check-disabled [{:keys [host/context] :as fixture}]
  (swap! context update :execution/opts dissoc :opsv/experiment-execution)
  (let [[result calls] (fixtures/execute fixture)]
    (is (anomaly/anomaly? result))
    (is (empty? calls))
    (is (empty? (fixtures/dispositions fixture)))))

(defn- ^{:stratum 0} check-late-refusal [on-read! {:keys [host/runtime] :as fixture}]
  (let [[result calls] (fixtures/execute fixture on-read!)
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (anomaly/anomaly? result))
    (is (= :failed (:effect/state record)))
    (is (empty? (fixtures/mutations calls)))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-foreign-fence [{:keys [host/runtime] :as fixture}]
  (let [foreign (actuation/create-mutation-fence)
        [result calls] (fixtures/execute (assoc-in fixture [:host/runtime :fence] foreign))]
    (is (anomaly/anomaly? result))
    (is (empty? calls))
    (is (false? (:stopped? (actuation/mutation-status foreign))))
    (is (false? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-replay [fixture]
  (let [[first-result _calls] (fixtures/execute fixture)
        [replayed calls] (fixtures/execute fixture)]
    (is (= :succeeded (:effect/state first-result)))
    (is (= :conflict (:anomaly/type replayed)))
    (is (= first-result (get-in replayed [:anomaly/data :effect/transaction])))
    (is (empty? calls))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} change-policy! [fixture calls _options]
  (when (= claimed-provider-pod-read (fixtures/pod-reads fixture calls))
    (swap! (:host/context fixture) assoc-in
           [:execution/opts :opsv/governance :policy/context :opsv/allowed-environments] #{})))

(defn- ^{:stratum 1} stop! [fixture calls _options]
  (when (= claimed-provider-pod-read (fixtures/pod-reads fixture calls))
    (phase/stop-supervised-runs! (:host/supervisor fixture) clock/now)))

(defn- ^{:stratum 1} revoke! [fixture calls _options]
  (when (= claimed-provider-pod-read (fixtures/pod-reads fixture calls))
    (let [grant-id (get-in (first (fixtures/dispositions fixture)) [:opsv/governed-effect :evidence/grant-id])]
      (grant/revoke-stored! (get-in fixture [:host/runtime :authority-directory]) grant-id
                             :revocation/operator clock/now))))

(defn- ^{:stratum 1} expire! [fixture calls _options]
  (when (= claimed-provider-pod-read (fixtures/pod-reads fixture calls))
    (reset! (:host/clock fixture) (.plusSeconds clock/now beyond-grant-lifetime-seconds))))

(deftest ^{:stratum 1} exact-native-start-has-real-current-governance-authority-and-three-durable-audit-dispositions-test
  (fixtures/with-startable check-success))

(deftest ^{:stratum 1} default-disabled-experiments-refuse-before-provider-reads-or-authority-test
  (fixtures/with-startable check-disabled))

(deftest ^{:stratum 1} mismatched-opaque-runtime-fence-is-not-used-or-stopped-test
  (fixtures/with-startable check-foreign-fence))

(deftest ^{:stratum 1} replay-requires-reconciliation-without-new-grants-or-provider-calls-test
  (fixtures/with-startable check-replay))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} changed-current-n4-policy-after-claim-refuses-before-gate-release-test
  (fixtures/with-startable (partial check-late-refusal change-policy!)))

(deftest ^{:stratum 2} actual-supervisor-stop-after-claim-refuses-before-gate-release-test
  (fixtures/with-startable (partial check-late-refusal stop!)))

(deftest ^{:stratum 2} actual-current-grant-revocation-after-claim-refuses-before-gate-release-test
  (fixtures/with-startable (partial check-late-refusal revoke!)))

(deftest ^{:stratum 2} current-grant-expiry-during-native-reconstruction-refuses-before-gate-release-test
  (fixtures/with-startable (partial check-late-refusal expire!)))

(comment
  (fixtures/with-startable check-success))
