;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-recovery-plan-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as retained-read]
            [ai.miniforge.phase-opsv.experiment-preflight-fixtures :as preflight]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.governed-replica-proposal-fixtures :as retained]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replica]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} outside-population-count
  "Valid replica count outside the fixture's two-to-three replica population bounds." 99)

(defn- ^{:stratum 0} plan-input [load]
  {:load load :recovery/duration-ms (get-in load [:limits :opsv/duration-ms])})

(defn- ^{:stratum 0} original [fixture]
  (get-in fixture [:value :artifact/content :comparison/preparation :environment/raw-observations
                   :kubernetes/verification :kubernetes/source :kubernetes/inventory :kubernetes/deployment]))

(defn- ^{:stratum 0} repin [input contract]
  (assoc-in input [:load :request :comparison/pin] (phase/pin-verification-environment contract)))

(defn- ^{:stratum 0} malformed-delta [value pack]
  (update-in pack [:experiment-pack/guardrails :blast-radius]
             #(if (= ::missing value) (dissoc % :replica-delta) (assoc % :replica-delta value))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-malformed-delta [{:keys [ctx input id]}]
  (let [input (fixtures/input input)
        request (:request input)
        digest (:experiment-pack/hash request)
        original (retained-read/read! ctx request id)]
    (is (= digest (hash/content-hash (:experiment-pack request))))
    (is (= digest (get-in request [:comparison/pin :comparison/contract :comparison/binding :experiment-pack/hash])))
    (is (and (map? original) (not (anomaly/anomaly? original))))
    (is (= :invalid-input (:anomaly/type (phase/prepare-kubernetes-recovery-plan! ctx (plan-input input)))))))

(defn- ^{:stratum 1} check-plan [{:keys [ctx input id] :as fixture}]
  (let [load (phase/prepare-governed-kubernetes-load-proposal! ctx input)
        result (phase/prepare-kubernetes-recovery-plan! ctx (plan-input input))
        plan (:recovery/plan result)
        deployment (original fixture)]
    (is (m/validate schema/Plan plan))
    (is (= (hash/content-hash plan) (:recovery/hash result)))
    (is (= (get-in input [:request :comparison/pin :comparison/contract :comparison/binding]) (:recovery/binding plan)))
    (is (= id (:comparison/artifact-ref plan)))
    (is (= [:stop-load :cleanup-load :restore-baseline] (:recovery/operations plan)))
    (is (= 3 (:recovery/max-effects plan)))
    (is (= (get-in input [:limits :opsv/duration-ms]) (:recovery/duration-ms plan)))
    (is (= (get-in input [:limits :kubernetes/context]) (:kubernetes/context plan)))
    (is (= (get-in input [:request :comparison/pin :comparison/hash]) (:comparison/hash plan)))
    (is (= (get-in deployment ["metadata" "uid"]) (get-in plan [:recovery/restore :resource :uid])))
    (is (= (get-in deployment ["spec" "replicas"]) (get-in plan [:recovery/restore :replicas/count])))
    (is (= (:effect/id load) (get-in plan [:recovery/load :creation-effect/id])))
    (is (= (get-in load [:opsv/experiment :opsv/payload-hash]) (get-in plan [:recovery/load :creation-payload/hash])))
    (is (= (get-in load [:opsv/experiment :opsv/resources 0]) (get-in plan [:recovery/load :resource])))
    (is (= :durable-creation-receipt-and-live-uid (get-in plan [:recovery/load :ownership])))
    (is (= :observed-container-termination (get-in plan [:recovery/load :termination])))
    (is (not (contains? result :envelope/decision)))
    (is (not (contains? result :grant/id)))
    (is (= result (phase/prepare-kubernetes-recovery-plan! ctx (plan-input input))))
    (is (not (contains? (:recovery/restore plan) :kubernetes/request)))
    (is (not (contains? (:recovery/restore plan) :resource-version)))))

(defn- ^{:stratum 1} check-bounds [{:keys [ctx input]}]
  (let [value (plan-input input)]
    (doseq [[path replacement] [[[:recovery/duration-ms] 0] [[:recovery/duration-ms] Long/MAX_VALUE]
                                [[:recovery/duration-ms] nil] [[:recovery/operations] [:trial-apply]]
                                [[:recovery/restore] {:replicas/count outside-population-count}]
                                [[:load :current] {}] [[:load :limits :opsv/load-limits :load/max-requests] 0]
                                [[:load :declaration :workflow/id] (random-uuid)]
                                [[:load :declaration :runtime/target :workload-uid] "replacement"]
                                [[:load :comparison/artifact-ref] (random-uuid)]]]
      (is (anomaly/anomaly? (phase/prepare-kubernetes-recovery-plan! ctx (assoc-in value path replacement)))))))

(defn- ^{:stratum 1} check-load-binding [{:keys [ctx input]}]
  (let [first-plan (phase/prepare-kubernetes-recovery-plan! ctx (plan-input input))
        changed (assoc-in input [:limits :effect/id] (random-uuid))
        second-plan (phase/prepare-kubernetes-recovery-plan! ctx (plan-input changed))]
    (is (map? (:recovery/plan second-plan)))
    (is (not= (:recovery/hash first-plan) (:recovery/hash second-plan)))
    (is (not= (get-in first-plan [:recovery/plan :recovery/load :creation-payload/hash])
              (get-in second-plan [:recovery/plan :recovery/load :creation-payload/hash])))
    (is (= (get-in first-plan [:recovery/plan :recovery/restore])
           (get-in second-plan [:recovery/plan :recovery/restore])))))

(defn- ^{:stratum 1} check-original [{:keys [ctx input]}]
  (let [value (plan-input input)
        contract (get-in input [:request :comparison/pin :comparison/contract])]
    (doseq [changed [(dissoc contract :comparison/replica-contract)
                     (assoc-in contract [:comparison/binding :verification/id] (random-uuid))
                     (assoc-in contract [:comparison/replica-contract :rollback :replicas/min-count] outside-population-count)]]
      (is (anomaly/anomaly? (phase/prepare-kubernetes-recovery-plan! ctx (repin value changed)))))))

(defn- ^{:stratum 1} check-acknowledgement [{:keys [ctx input id]}]
  (let [store (:opsv/evidence-assembly-store ctx)
        bundle (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        assembly (evidence/get-opsv-assembly store bundle)
        value (plan-input input)]
    (is (map? (:recovery/plan (phase/prepare-kubernetes-recovery-plan! ctx value))))
    (swap! store assoc-in [bundle :opsv/artifact-refs] (disj (:opsv/artifact-refs assembly) id))
    (is (anomaly/anomaly? (phase/prepare-kubernetes-recovery-plan! ctx value)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} bounded-plan-retains-original-identity-and-exact-planned-load-without-grant-test
  (fixtures/with-input check-plan))

(deftest ^{:stratum 2} malformed-unbounded-and-cross-target-plans-are-refused-test
  (fixtures/with-input check-bounds))

(deftest ^{:stratum 2} changed-load-creation-requires-a-distinct-recovery-plan-test
  (fixtures/with-input check-load-binding))

(deftest ^{:stratum 2} original-recovery-membership-and-attempt-pin-cannot-be-replaced-test
  (fixtures/with-input check-original))

(deftest ^{:stratum 2} acknowledgement-is-read-again-for-every-plan-test
  (fixtures/with-input check-acknowledgement))

(deftest ^{:stratum 2} malformed-replica-delta-in-an-acknowledged-correlated-original-is-an-anomaly-test
  (doseq [value [::missing nil "unbounded" -1 0.5 Long/MAX_VALUE]]
    (retained/with-input #(replica/input (preflight/input (partial malformed-delta value))) check-malformed-delta)))

(comment ::real-retained-producer-storage-and-acknowledgement-no-provider-execution)
