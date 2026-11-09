;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-retention-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [ai.miniforge.phase-opsv.recovery-plan-material-schema :as schema]
            [ai.miniforge.phase-opsv.recovery-plan-retention-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} revoke-ack! [ctx id]
  (let [store (:opsv/evidence-assembly-store ctx)
        bundle (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        assembly (evidence/get-opsv-assembly store bundle)]
    (swap! store assoc-in [bundle :opsv/artifact-refs] (disj (:opsv/artifact-refs assembly) id))))

(defn- ^{:stratum 0} check-approved [{:keys [ctx prepared plan-id retained-output] :as fixture}]
  (let [value (fixtures/published fixture)
        report (:artifact/content value)
        result (fixtures/read-plan fixture)]
    (is (= :opsv-verification-report (:artifact/type value)))
    (is (= :recovery-plan-admission (:verification/kind report)))
    (is (m/validate schema/Content report))
    (is (protected/valid? ctx value))
    (is (some? (get-in value [:artifact/metadata :evidence/content-protection])))
    (is (true? (:verification/passed? report)))
    (is (= 6 (count (:verification/criteria report))))
    (is (every? true? (vals (:verification/criteria report))))
    (is (= (:recovery/plan prepared) (:recovery/plan report) (:recovery/plan result)))
    (is (= (:recovery/hash prepared) (:recovery/hash result)))
    (is (= plan-id (:recovery/artifact-ref result)))
    (is (= [plan-id] (:opsv/phase-produced-artifact-refs retained-output)))
    (is (= result (fixtures/read-plan fixture)))
    (is (not (contains? result :grant/id)))
    (is (not (contains? result :recovery/plan-decision-envelope)))))

(defn- ^{:stratum 0} check-approval-revocation [fixture]
  (let [changed (assoc-in fixture [:ctx :execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes]
                          #{(hash/content-hash :different-plan)})]
    (is (= :unauthorized (:anomaly/type (fixtures/read-plan changed))))
    (is (map? (:recovery/plan (fixtures/read-plan fixture))))))

(defn- ^{:stratum 0} check-policy-revision [fixture]
  (let [changed (assoc-in fixture [:ctx :execution/opts :opsv/governance :policy/revision] "recovery-policy-next")
        renewed (fixtures/retain changed)
        new-id (:recovery/artifact-ref renewed)
        progressed (update-in fixture [:ctx :execution/opts :opsv/governance :policy/event-watermark] inc)]
    (is (= :conflict (:anomaly/type (fixtures/read-plan changed))))
    (is (not= (:plan-id fixture) new-id))
    (is (map? (:recovery/plan (fixtures/read-plan (assoc changed :plan-id new-id)))))
    (is (map? (:recovery/plan (fixtures/read-plan progressed))))))

(defn- ^{:stratum 0} check-denied-report [fixture]
  (let [changed (assoc-in fixture [:ctx :execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes]
                          #{(hash/content-hash :not-approved)})
        output (fixtures/retain changed)
        selected (assoc fixture :plan-id (:recovery/artifact-ref output))
        report (:artifact/content (fixtures/published selected))]
    (is (uuid? (:recovery/artifact-ref output)))
    (is (false? (:verification/passed? report)))
    (is (some false? (vals (:verification/criteria report))))
    (is (= :conflict (:anomaly/type (fixtures/read-plan selected))))))

(defn- ^{:stratum 0} check-substitutes [{:keys [ctx id] :as fixture}]
  (let [content (:artifact/content (fixtures/published fixture))
        legacy (protected/record ctx schema/kind schema/artifact-type content nil)]
    (is (anomaly/anomaly? (fixtures/read-plan (assoc fixture :plan-id id))))
    (doseq [descriptor [[:verification-environment schema/artifact-type schema/content-key]
                        [schema/kind :review schema/content-key]]]
      (let [other (fixtures/publish! fixture (fixtures/record fixture content descriptor))]
        (is (anomaly/anomaly? (fixtures/read-plan (assoc fixture :plan-id other))))))
    (let [legacy-id (fixtures/publish! fixture legacy)]
      (is (protected/valid? ctx (fixtures/published (assoc fixture :plan-id legacy-id))))
      (is (anomaly/anomaly? (fixtures/read-plan (assoc fixture :plan-id legacy-id)))))))

(defn- ^{:stratum 0} check-semantic-substitution [fixture]
  (let [content (:artifact/content (fixtures/published fixture))
        changed-plan (update-in content [:recovery/plan :recovery/restore :replicas/count] inc)
        rebound (assoc changed-plan :recovery/hash (hash/content-hash (:recovery/plan changed-plan)))
        first-gate (first (keys (:verification/criteria content)))
        false-criterion (assoc-in content [:verification/criteria first-gate] false)]
    (doseq [changed [rebound false-criterion]]
      (let [value (fixtures/record fixture changed schema/material)
            id (fixtures/publish! fixture value)]
        (is (m/validate schema/Content (:artifact/content value)))
        (is (protected/valid? (:ctx fixture) value))
        (is (= :conflict (:anomaly/type (fixtures/read-plan (assoc fixture :plan-id id)))))))))

(defn- ^{:stratum 0} check-producer-substitution [fixture]
  (let [content (:artifact/content (fixtures/published fixture))]
    (doseq [path [[:ctx :execution/id] [:ctx :execution/input :opsv/evidence-bundle-id]]]
      (let [other (assoc-in fixture path (random-uuid))
            value (fixtures/record other content schema/material)
            id (fixtures/publish! fixture value)]
        (is (protected/valid? (:ctx other) value))
        (is (= :conflict (:anomaly/type (fixtures/read-plan (assoc fixture :plan-id id)))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} approved-plan-has-real-protected-retention-and-no-effect-authority-test
  (fixtures/with-retained check-approved))

(deftest ^{:stratum 1} withdrawn-plan-or-original-acknowledgement-refuses-every-fresh-read-test
  (doseq [reference-key [:plan-id :id]]
    (fixtures/with-retained
      (fn [{:keys [ctx] :as fixture}]
        (is (map? (:recovery/plan (fixtures/read-plan fixture))))
        (revoke-ack! ctx (get fixture reference-key))
        (is (anomaly/anomaly? (fixtures/read-plan fixture)))))))

(deftest ^{:stratum 1} runtime-approval-is-rechecked-not-cached-from-retained-success-test
  (fixtures/with-retained check-approval-revocation))

(deftest ^{:stratum 1} changed-policy-requires-new-retention-but-journal-progress-does-not-test
  (fixtures/with-retained check-policy-revision))

(deftest ^{:stratum 1} retained-denial-does-not-turn-into-an-approval-after-configuration-changes-test
  (fixtures/with-input check-denied-report))

(deftest ^{:stratum 1} wrong-kind-type-original-and-unprotected-legacy-reports-are-refused-test
  (fixtures/with-retained check-substitutes))

(deftest ^{:stratum 1} valid-protected-but-wrong-plan-or-inconsistent-criteria-are-refused-test
  (fixtures/with-retained check-semantic-substitution))

(deftest ^{:stratum 1} actual-protected-report-from-another-workflow-or-bundle-is-refused-test
  (fixtures/with-retained check-producer-substitution))

(comment ::actual-protected-producers-and-acknowledgements-no-fake-recovery-success)
