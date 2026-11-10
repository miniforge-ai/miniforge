;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.boundary.kubernetes-verification-preparation :as acquisition]
            [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery-fixtures :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-fixtures :as fingerprint]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as capture]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fault-fixtures :as faults]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as target]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [malli.core :as m]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-refused [transform reads? fixture]
  (let [[result calls] (fixtures/execute (transform fixture))]
    (is (anomaly/anomaly? result))
    (is (= reads? (boolean (seq calls))))
    (is (empty? (fixtures/mutations calls)))
    (is (empty? (fs/glob (str (get-in fixture [:host/runtime :authority-directory]) "/grants") "*.grant.edn")))
    (is (empty? (forward/dispositions fixture)))))

(defn- ^{:stratum 0} check-success [{:keys [host/runtime ctx] :as fixture}]
  (let [[result calls] (fixtures/execute fixture)
        patch (first (fixtures/mutations calls))
        audit (forward/dispositions fixture)
        assembly (forward/assembly fixture)
        readiness (fixtures/readiness-artifact fixture)
        retained (:artifact/content readiness)]
    (is (= :succeeded (:effect/state result)))
    (is (= :granted (:effect/authority result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id result))))
    (is (= (get-in fixture [:request :effect/id]) (:effect/id result)))
    (is (= :trial-apply (get-in result [:effect/proposal :opsv/experiment :opsv/operation])))
    (is (= (get-in fixture [:request :replicas/count]) (get-in result [:effect/observed :replicas/count])))
    (is (= fixtures/committed-revision (get-in result [:effect/observed :kubernetes/resource-version])))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= :patch (:method (last calls))))
    (is (every? #(= :get (:method %)) (butlast calls)))
    (is (= (get-in result [:effect/proposal :kubernetes/request :body]) (json/parse-string (:body patch))))
    (is (= [:proposed :committing :succeeded] (mapv :opsv/effect-state audit)))
    (is (every? (:opsv/event-refs assembly) (map :event/id audit)))
    (is (contains? (:opsv/grant-refs assembly) (:effect/grant-id result)))
    (is (some? readiness))
    (is (protected/valid? ctx readiness))
    (is (= :committing (get-in retained [:effect/transaction :effect/state])))
    (is (= (:effect/id result) (get-in retained [:effect/transaction :effect/id])))
    (is (= (:effect/grant-id result) (get-in retained [:effect/transaction :effect/grant-id])))
    (is (= (:effect/proposal result) (get-in retained [:effect/transaction :effect/proposal])))
    (is (m/validate decision/AllowingEnvelope (:opsv/commit-decision-envelope retained)))
    (is (seq (get-in retained [:comparison/observed :environment/raw-observations])))
    (is (not-any? #(contains? (:effect/observed result) %) [:load/started? :slo/passed? :recovery/verified?]))))

(defn- ^{:stratum 0} check-replay [fixture]
  (let [[first-result _] (fixtures/execute fixture)
        [result calls] (fixtures/execute fixture)]
    (is (= :succeeded (:effect/state first-result)))
    (is (= :conflict (:anomaly/type result)))
    (is (= first-result (get-in result [:anomaly/data :effect/transaction])))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-unknown [{:keys [host/runtime] :as fixture}]
  (let [pending (wire/failed-future (java.io.IOException.))
        [result calls] (fixtures/execute-response fixture pending (constantly nil))
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (= :unknown-outcome (:effect/state record)))
    (is (= record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))
    (is (some? (:grant/revoked-at (fixtures/current-grant fixture))))))

(defn- ^{:stratum 0} change-on-claim! [change armed fixture _calls _options]
  (when (and (= :committing (:opsv/effect-state (last (forward/dispositions fixture))))
             (compare-and-set! armed true false))
    (fixtures/change! change fixture)))

(defn- ^{:stratum 0} check-fenced-without-mutation [expected runtime result calls]
  (is (anomaly/anomaly? result))
  (is (= expected (get-in result [:anomaly/data :effect/transaction :effect/state])))
  (is (seq calls))
  (is (empty? (fixtures/mutations calls)))
  (is (true? (:stopped? (actuation/mutation-status (:fence runtime))))))

(defn- ^{:stratum 0} check-drift-evidence [{:keys [ctx] :as fixture}]
  (let [value (fixtures/material-artifact :native-replica-trial-comparison fixture)
        report (:artifact/content value)
        observed (:comparison/observed report)
        raw (if (anomaly/anomaly? observed)
              (get-in observed [:anomaly/data :environment/raw-observations])
              (:environment/raw-observations observed))
        events (recovery/drift-events fixture)
        assembly (forward/assembly fixture)]
    (is (some? value))
    (is (protected/valid? ctx value))
    (is (= :native-replica-trial-comparison (:verification/kind report)))
    (is (false? (:verification/passed? report)))
    (is (seq raw))
    (is (seq (get-in report [:comparison/original :environment/raw-observations])))
    (is (anomaly/anomaly? (:comparison/result report)))
    (is (= (get-in fixture [:prepared :recovery/hash]) (:recovery/hash report)))
    (is (contains? (:opsv/artifact-refs assembly) (:artifact/id value)))
    (is (= 1 (count events)))
    (is (contains? (:opsv/event-refs assembly) (:event/id (first events))))))

(defn- ^{:stratum 0} check-unavailable-capture [{:keys [ctx] :as fixture}]
  (let [failure (anomaly/anomaly :unavailable "injected native acquisition failure" {})
        [result calls] (with-redefs [acquisition/observe! (constantly failure)] (fixtures/execute fixture))
        value (fixtures/material-artifact :native-replica-trial-comparison fixture)
        report (:artifact/content value)]
    (is (= :unavailable (:anomaly/type result)))
    (is (empty? calls))
    (is (false? (:verification/passed? report)))
    (is (= failure (:comparison/result report)))
    (is (protected/valid? ctx value))
    (is (empty? (recovery/drift-events fixture)))
    (is (contains? (:opsv/artifact-refs (forward/assembly fixture)) (:artifact/id value)))))

(defn- ^{:stratum 0} check-drift-publication-failure [fixture]
  (let [path [:host/routes inventory/deployment-path "spec" "replicas"]
        changed (assoc-in fixture path replicas/trial-count)
        failure (anomaly/anomaly :unavailable "injected comparison publication failure" {})
        [result calls] (with-redefs [artifact/publish! (constantly failure)] (fixtures/execute changed))
        output (get-in result [:anomaly/data :opsv/phase-output])
        report (:opsv/native-replica-trial-comparison output)]
    (is (anomaly/anomaly? result))
    (is (false? (:verification/passed? report)))
    (is (seq (get-in report [:comparison/observed :environment/raw-observations])))
    (is (empty? (:opsv/phase-produced-artifact-refs output)))
    (is (= 1 (count (recovery/drift-events fixture))))
    (is (empty? (fixtures/mutations calls)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-late-refusal [change {:keys [host/runtime] :as fixture}]
  (let [armed (atom true)
        [result calls] (fixtures/execute fixture (partial change-on-claim! change armed))]
    (is (false? @armed))
    (check-fenced-without-mutation :failed runtime result calls)))

(defn- ^{:stratum 1} check-commit-drift [path value {:keys [host/runtime] :as fixture}]
  (let [[result calls] (faults/execute-with-drift path value fixture)]
    (check-fenced-without-mutation :failed runtime result calls)))

(defn- ^{:stratum 1} check-revised-proposal [{:keys [host/runtime] :as fixture}]
  (let [[result calls] (faults/execute-with-revision-change fixture)
        record (get-in result [:anomaly/data :effect/transaction])]
    (check-fenced-without-mutation :unknown-outcome runtime result calls)
    (is (= record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (some? (:grant/revoked-at (fixtures/current-grant fixture))))))

(defn- ^{:stratum 1} check-publication-failure [{:keys [host/runtime] :as fixture}]
  (let [{:keys [result calls artifact retention]} (faults/execute-with-publication-failure fixture)
        content (:artifact/content artifact)
        returned (get-in retention [:result :anomaly/data :opsv/phase-output :opsv/native-replica-trial-readiness])]
    (is (seq content))
    (is (some? (get-in artifact [:artifact/metadata :evidence/content-protection])))
    (is (not= (:raw retention) content))
    (is (= content returned))
    (is (seq (get-in returned [:comparison/observed :environment/raw-observations])))
    (check-fenced-without-mutation :failed runtime result calls)))

(defn- ^{:stratum 1} check-initial-drift [path value fixture]
  (let [changed (assoc-in fixture (into [:host/routes] path) value)
        [result calls] (fixtures/execute changed)
        output (get-in result [:anomaly/data :opsv/phase-output])]
    (is (anomaly/anomaly? result))
    (is (empty? (fixtures/mutations calls)))
    (is (seq (:opsv/phase-produced-artifact-refs output)))
    (check-drift-evidence fixture)))

(defn- ^{:stratum 1} check-late-drift-evidence [fixture]
  (let [path [inventory/deployment-path "spec" "replicas"]
        [result calls] (faults/execute-with-drift path replicas/trial-count fixture)]
    (check-fenced-without-mutation :failed (:host/runtime fixture) result calls)
    (check-drift-evidence fixture)))

(deftest ^{:stratum 1} exact-candidate-has-current-governance-authority-and-three-durable-audit-dispositions-test
  (fixtures/with-executable check-success))

(deftest ^{:stratum 1} disabled-unretained-foreign-and-serialized-authority-inputs-refuse-before-native-reads-test
  (doseq [transform [fixtures/disabled
                     #(assoc-in % [:request :recovery/artifact-ref] (random-uuid))
                     #(assoc-in % [:request :trial/provider :kubernetes/context] "foreign-context")
                     #(assoc-in % [:request :approved?] true)
                     #(assoc-in % [:request :current] {})
                     #(assoc-in % [:request :replicas/count] nil)]]
    (fixtures/with-executable (partial check-refused transform false))))

(deftest ^{:stratum 1} changed-original-count-and-foreign-physical-roots-refuse-before-authority-test
  (doseq [[path value] [[[inventory/deployment-path "spec" "replicas"] replicas/trial-count]
                       [[target/cluster-path "metadata" "uid"] "foreign-cluster"]
                       [[inventory/namespace-path "metadata" "uid"] "foreign-namespace"]]]
    (fixtures/with-executable (partial check-refused #(assoc-in % (into [:host/routes] path) value) true))))

(deftest ^{:stratum 1} replay-requires-reconciliation-without-another-native-patch-test
  (fixtures/with-executable check-replay))

(deftest ^{:stratum 1} uncertain-native-candidate-is-durable-fenced-and-revoked-without-retry-test
  (fixtures/with-executable check-unknown))

(deftest ^{:stratum 1} unavailable-native-acquisition-is-retained-without-inventing-environment-drift-test
  (fixtures/with-executable check-unavailable-capture))

(deftest ^{:stratum 1} failed-comparison-publication-retains-protected-failure-without-claiming-artifact-acknowledgement-test
  (fixtures/with-executable check-drift-publication-failure))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} changed-policy-stop-revocation-and-expiry-after-claim-refuse-before-mutation-test
  (doseq [change [:policy :stop :revoke :expiry]]
    (fixtures/with-executable (partial check-late-refusal change))))

(deftest ^{:stratum 2} drift-after-preparation-is-reobserved-at-claimed-commit-before-any-patch-test
  (doseq [[path value] [[[inventory/deployment-path "spec" "replicas"] replicas/trial-count]
                       [[inventory/deployment-path "metadata" "uid"] "foreign-workload"]
                       [[inventory/namespace-path "metadata" "uid"] "foreign-namespace"]]]
    (fixtures/with-executable (partial check-commit-drift path value))))

(deftest ^{:stratum 2} readiness-publication-refusal-preserves-the-real-protected-native-observation-without-patching-test
  (fixtures/with-executable check-publication-failure))

(deftest ^{:stratum 2} revised-native-request-after-claim-refuses-without-reusing-the-old-payload-grant-test
  (fixtures/with-executable check-revised-proposal))

(deftest ^{:stratum 2} initial-count-environment-and-unprojectable-native-drift-retain-linked-protected-comparison-test
  (doseq [[path value] [[[inventory/deployment-path "spec" "replicas"] replicas/trial-count]
                       [[capture/config-path "data" "workers"] "8"]
                       [[inventory/pods-path "items" 0 "status" "containerStatuses" 0 "imageID"] (fingerprint/other-image)]]]
    (fixtures/with-executable (partial check-initial-drift path value))))

(deftest ^{:stratum 2} commit-time-drift-retains-linked-observations-before-returning-the-failed-transaction-test
  (fixtures/with-executable check-late-drift-evidence))

(comment
  (fixtures/with-executable check-success))
