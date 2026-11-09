;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-recovery-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-fixtures :as fingerprint]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-unacknowledged [fixture]
  (let [[result calls] (with-redefs [evidence/accumulate-opsv-evidence! (constantly nil)]
                         (fixtures/verify fixture (get-in fixture [:native :routes])))]
    (is (anomaly/anomaly? result))
    (is (seq calls))
    (is (empty? (get-in result [:anomaly/data :opsv/phase-output :opsv/phase-produced-artifact-refs])))))

(def ^{:stratum 0} ^:private foreign-template-image
  "A valid immutable image reference not declared by the retained original template."
  "docker.io/foreign/service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- ^{:stratum 0} replacement [routes]
  (-> routes
      (assoc-in [inventory/pods-path "items" 0 "metadata" "uid"] "replacement-pod-uid")
      (assoc-in [inventory/pods-path "items" 0 "metadata" "name"] "catalog-replacement")))

(defn- ^{:stratum 0} check-retained [{:keys [ctx]} output]
  (let [id (get-in output [:opsv/phase-artifact-ids :baseline-recovery])
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                            (get-in ctx [:execution/input :opsv/evidence-bundle-id]))
        value (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory]) id)]
    (is (uuid? id))
    (is (contains? (:opsv/artifact-refs assembly) id))
    (is (= :opsv-verification-report (:artifact/type value)))
    (is (= :baseline-recovery (get-in value [:artifact/metadata :opsv/material-kind])))
    (is (= (:artifact/content value) (:opsv/baseline-recovery output)))
    (is (some? (get-in value [:artifact/metadata :evidence/content-protection])))
    (is (protected/valid? ctx value))))

(defn- ^{:stratum 0} check-refused [transform fixture]
  (let [[result calls] (fixtures/verify (transform fixture) (get-in fixture [:native :routes]))]
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

(defn- ^{:stratum 0} fail-publication [fixture]
  (let [failure (anomaly/anomaly :unavailable "injected publication failure" {})
        [result calls] (with-redefs [artifact/publish! (constantly failure)]
                         (fixtures/verify fixture (get-in fixture [:native :routes])))]
    (is (anomaly/anomaly? result))
    (is (seq calls))
    (is (true? (get-in result [:anomaly/data :opsv/phase-output :opsv/baseline-recovery :verification/passed?])))
    (is (empty? (get-in result [:anomaly/data :opsv/phase-output :opsv/phase-produced-artifact-refs])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-original [fixture]
  (let [[output calls] (fixtures/verify fixture (get-in fixture [:native :routes]))
        report (:opsv/baseline-recovery output)]
    (is (not (anomaly/anomaly? output)))
    (is (true? (:verification/passed? report)))
    (is (= (:plan-id fixture) (:recovery/artifact-ref report)))
    (is (= (get-in fixture [:input :load :request :comparison/pin]) (:comparison/pin report)))
    (is (seq (get-in report [:comparison/original :environment/raw-observations])))
    (is (seq (get-in report [:comparison/observed :environment/raw-observations])))
    (is (seq calls))
    (is (every? #(= :get (:method %)) calls))
    (is (empty? (fixtures/drift-events fixture)))
    (is (not-any? #(contains? report %) [:load/quiescence :load/absent? :recovery/verified? :slo/passed?]))
    (check-retained fixture output)))

(defn- ^{:stratum 1} check-drift [path value fixture]
  (let [routes (assoc-in (get-in fixture [:native :routes]) path value)
        [output calls] (fixtures/verify fixture routes)
        report (:opsv/baseline-recovery output)]
    (is (not (anomaly/anomaly? output)))
    (is (false? (:verification/passed? report)))
    (is (anomaly/anomaly? (:comparison/result report)))
    (is (every? #(= :get (:method %)) calls))
    (check-retained fixture output)))

(defn- ^{:stratum 1} check-unprojectable [fixture]
  (let [routes (assoc-in (get-in fixture [:native :routes])
                        [inventory/pods-path "items" 0 "status" "containerStatuses" 0 "imageID"]
                        (fingerprint/other-image))
        [output calls] (fixtures/verify fixture routes)
        report (:opsv/baseline-recovery output)
        observed (:comparison/observed report)
        capture (get-in observed [:anomaly/data :environment/raw-observations :kubernetes/verification :kubernetes/source])]
    (is (not (anomaly/anomaly? output)))
    (is (false? (:verification/passed? report)))
    (is (anomaly/anomaly? observed))
    (is (= observed (:comparison/result report)))
    (is (seq (:kubernetes/inventory capture)))
    (is (seq (get-in capture [:kubernetes/inventory :kubernetes/pod-inventory "items"])))
    (is (every? #(= :get (:method %)) calls))
    (check-retained fixture output)))

(defn- ^{:stratum 1} check-drift-event [{:keys [ctx] :as fixture}]
  (let [routes (assoc-in (get-in fixture [:native :routes])
                        [inventory/deployment-path "spec" "template" "spec" "containers" 0 "image"] foreign-template-image)
        [output _] (fixtures/verify fixture routes)
        observed (fixtures/drift-events fixture)
        event (first observed)
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                            (get-in ctx [:execution/input :opsv/evidence-bundle-id]))]
    (is (false? (get-in output [:opsv/baseline-recovery :verification/passed?])))
    (is (= 1 (count observed)))
    (is (= (:execution/id ctx) (:workflow/id event)))
    (is (= (get-in ctx [:execution/input :opsv/evidence-bundle-id]) (:opsv/evidence-bundle-id event)))
    (is (= (get-in fixture [:input :load :request :verification/id]) (get-in event [:opsv/deviation :verification/id])))
    (is (contains? (:opsv/event-refs assembly) (:event/id event)))
    (check-retained fixture output)))

(defn- ^{:stratum 1} check-drift-delivery-failure [fixture]
  (let [routes (assoc-in (get-in fixture [:native :routes])
                        [inventory/deployment-path "spec" "template" "spec" "containers" 0 "image"] foreign-template-image)
        failure (anomaly/anomaly :unavailable "injected drift delivery failure" {})
        [output _] (with-redefs [events/publish! (constantly failure)] (fixtures/verify fixture routes))
        report (:opsv/baseline-recovery output)]
    (is (not (anomaly/anomaly? output)))
    (is (false? (:verification/passed? report)))
    (is (= failure (get-in report [:comparison/result :anomaly/data :opsv/drift-publication-failure])))
    (is (empty? (fixtures/drift-events fixture)))
    (check-retained fixture output)))

(defn- ^{:stratum 1} check-missing-drift-stream [fixture]
  (let [changed (update fixture :ctx dissoc :event-stream)
        routes (assoc-in (get-in fixture [:native :routes])
                        [inventory/deployment-path "spec" "replicas"] replicas/trial-count)
        [output _] (fixtures/verify changed routes)
        report (:opsv/baseline-recovery output)]
    (is (false? (:verification/passed? report)))
    (is (= :unavailable (get-in report [:comparison/result :anomaly/data :opsv/drift-publication-failure :anomaly/type])))
    (check-retained changed output)))

(deftest ^{:stratum 1} missing-lineage-current-plan-approval-and-foreign-provider-refuse-before-any-native-reads-test
  (doseq [transform [#(assoc-in % [:restoration/request :recovery/artifact-ref] (random-uuid))
                     #(assoc-in % [:restoration/request :recovery/input :load :comparison/artifact-ref] (random-uuid))
                     #(assoc-in % [:restoration/request :restoration/provider :kubernetes/context] "foreign-context")
                     #(assoc-in % [:ctx :execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
                     #(assoc-in % [:restoration/request :approved?] true)]]
    (fixtures/with-input (partial check-refused transform))))

(deftest ^{:stratum 1} unacknowledged-evidence-is-not-a-successful-baseline-verification-result-test
  (fixtures/with-input fail-publication))

(deftest ^{:stratum 1} published-artifact-without-assembly-acknowledgement-is-not-verification-success-test
  (fixtures/with-input check-unacknowledged))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} heterogeneous-replicas-retain-fresh-raw-capture-when-semantic-projection-fails-test
  (fixtures/with-input check-unprojectable))

(deftest ^{:stratum 2} rollback-environment-drift-is-correlated-and-linked-to-the-evidence-assembly-test
  (fixtures/with-input check-drift-event))

(deftest ^{:stratum 2} drift-delivery-failure-remains-in-the-retained-failed-recovery-report-test
  (fixtures/with-input check-drift-delivery-failure))

(deftest ^{:stratum 2} missing-event-stream-is-retained-as-unresolved-drift-delivery-test
  (fixtures/with-input check-missing-drift-stream))

(deftest ^{:stratum 2} original-baseline-is-freshly-observed-compared-and-retained-without-mutation-or-whole-recovery-claims-test
  (fixtures/with-input check-original))

(deftest ^{:stratum 2} cancelled-forward-run-can-still-verify-independently-approved-baseline-recovery-test
  (fixtures/with-input #(check-original (assoc-in % [:ctx :execution/status] :cancelled))))

(deftest ^{:stratum 2} candidate-configuration-and-foreign-physical-identities-cannot-be-adopted-as-recovery-baseline-test
  (doseq [[path value] [[[inventory/deployment-path "spec" "replicas"] replicas/trial-count]
                       [[inventory/deployment-path "spec" "template" "spec" "containers" 0 "image"] foreign-template-image]
                       [[native/cluster-path "metadata" "uid"] "foreign-cluster-uid"]
                       [[inventory/namespace-path "metadata" "uid"] "foreign-namespace-uid"]
                       [[inventory/deployment-path] nil]]]
    (fixtures/with-input (partial check-drift path value))))

(deftest ^{:stratum 2} original-replica-identities-may-expire-within-predeclared-recovery-membership-test
  (fixtures/with-input #(check-original (update-in % [:native :routes] replacement))))

(comment
  (fixtures/with-input check-original))
