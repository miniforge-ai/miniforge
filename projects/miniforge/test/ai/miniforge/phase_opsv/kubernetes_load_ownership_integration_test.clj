;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-ownership-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-owned [{:keys [record pod] :as fixture}]
  (let [[result calls] (fixtures/observe fixture)
        request (first calls)]
    (is (= record (:load/creation-record result)))
    (is (= pod (:load/pod result)))
    (is (= #{:load/creation-record :load/pod} (set (keys result))))
    (is (= "fresh-read-revision" (get-in result [:load/pod "metadata" "resourceVersion"])))
    (is (= 1 (count calls)))
    (is (= :get (:method request)))
    (is (= (str "https://cluster.invalid/api/v1/namespaces/staging/pods/"
                (get-in pod ["metadata" "name"])) (:uri request)))
    (is (= result (first (fixtures/observe fixture))))))

(defn- ^{:stratum 0} check-plan-binding [fixture]
  (doseq [[path value] [[[:recovery/binding :workflow/id] (random-uuid)]
                       [[:recovery/binding :verification/id] (random-uuid)]
                       [[:recovery/binding :candidate/hash] (hash/content-hash :other-candidate)]
                       [[:recovery/binding :experiment-pack/hash] (hash/content-hash :other-pack)]
                       [[:comparison/artifact-ref] (random-uuid)]
                       [[:comparison/hash] (hash/content-hash :other-original)]
                       [[:kubernetes/context] "other-context"]
                       [[:recovery/restore :resource :name] "other-service"]
                       [[:recovery/load :creation-payload/hash] (hash/content-hash :other-payload)]
                       [[:recovery/load :creation-effect/id] (random-uuid)]]]
    (let [[result calls] (fixtures/observe (update fixture :plan assoc-in path value))]
      (is (anomaly/anomaly? result))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-provider-binding [fixture]
  (doseq [[key value] [[:kubernetes/cluster-id "other-cluster"]
                       [:kubernetes/namespace "other-namespace"] [:kubernetes/context "other-context"]]]
    (let [[result calls] (fixtures/observe (assoc-in fixture [:runtime :provider key] value))]
      (is (anomaly/anomaly? result))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-live-identity [{:keys [pod] :as fixture}]
  (doseq [changed [(assoc-in pod ["metadata" "uid"] "reused-name-foreign-uid")
                   (assoc-in pod ["metadata" "name"] "other-name")
                   (assoc-in pod ["metadata" "namespace"] "other-namespace")
                   (update pod "metadata" dissoc "resourceVersion") nil]]
    (let [[result calls] (fixtures/observe fixture changed)]
      (is (anomaly/anomaly? result))
      (is (= 1 (count calls))))))

(defn- ^{:stratum 0} check-current-record [{:keys [record] :as fixture}]
  (is (map? (:load/pod (first (fixtures/observe fixture)))))
  (fixtures/replace-record! fixture (assoc record :effect/state :unknown-outcome))
  (let [[result calls] (fixtures/observe fixture)]
    (is (= :conflict (:anomaly/type result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-canonical-payload [{:keys [record] :as fixture}]
  (let [request (assoc-in (get-in record [:effect/proposal :kubernetes/request])
                          [:body "spec" "containers" 0 "args"] ["unapproved-worker-command"])
        digest (hash/content-hash request)
        changed (-> record (assoc-in [:effect/proposal :kubernetes/request] request)
                    (assoc-in [:effect/proposal :opsv/experiment :opsv/payload-hash] digest))
        selected (assoc-in fixture [:plan :recovery/load :creation-payload/hash] digest)]
    (is (effect/valid? changed))
    (fixtures/replace-record! fixture changed)
    (let [[result calls] (fixtures/observe selected)]
      (is (= :conflict (:anomaly/type result)))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-unproven-records [{:keys [record] :as fixture}]
  (doseq [changed [(assoc record :effect/state :proposed)
                   (assoc record :effect/state :committing)
                   (assoc record :effect/state :failed)
                   (assoc record :effect/state :reconciled :effect/matched? true)
                   (assoc record :effect/matched? false)
                   (assoc record :effect/authority :unenforced)
                   (assoc record :effect/grant-id nil)
                   (dissoc record :effect/observed)]]
    (fixtures/replace-record! fixture changed)
    (let [[result calls] (fixtures/observe fixture)]
      (is (= :conflict (:anomaly/type result)))
      (is (empty? calls)))
    (fixtures/replace-record! (assoc fixture :record changed) record)))

(defn- ^{:stratum 0} check-changed-worker [{:keys [pod] :as fixture}]
  (let [changed (-> pod (assoc-in ["spec" "containers" 0 "image"] "unexpected-image")
                    (assoc-in ["status" "phase"] "Failed"))
        [result calls] (fixtures/observe fixture changed)]
    (is (= changed (:load/pod result)))
    (is (= #{:load/pod :load/creation-record} (set (keys result))))
    (is (= 1 (count calls)))))

(defn- ^{:stratum 0} check-revoked-forward-grant [{:keys [runtime record] :as fixture}]
  (let [directory (:effects-directory runtime)
        id (:effect/grant-id record)
        revoked (grant/revoke-stored! directory id :revocation/operator clock/now)
        [result calls] (fixtures/observe fixture)]
    (is (some? (:grant/revoked-at revoked)))
    (is (some? (:grant/revoked-at (grant/current directory id))))
    (is (map? (:load/pod result)))
    (is (= #{:load/pod :load/creation-record} (set (keys result))))
    (is (= 1 (count calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} actual-durable-granted-creation-and-fresh-provider-uid-establish-only-ownership-test
  (fixtures/with-created check-owned))

(deftest ^{:stratum 1} changed-attempt-original-target-context-or-payload-refuses-before-http-test
  (fixtures/with-created check-plan-binding))

(deftest ^{:stratum 1} runtime-provider-must-match-the-exact-created-target-test
  (fixtures/with-created check-provider-binding))

(deftest ^{:stratum 1} copied-labels-or-reused-name-do-not-prove-live-identity-test
  (fixtures/with-created check-live-identity))

(deftest ^{:stratum 1} durable-creation-is-reread-and-uncertainty-never-adopts-by-name-test
  (fixtures/with-created check-current-record))

(deftest ^{:stratum 1} rebound-hashes-do-not-authorize-a-noncanonical-creation-request-test
  (fixtures/with-created check-canonical-payload))

(deftest ^{:stratum 1} incomplete-ungranted-and-generic-reconciled-records-do-not-prove-native-creation-test
  (fixtures/with-created check-unproven-records))

(deftest ^{:stratum 1} owned-but-changed-worker-is-observed-without-lifecycle-or-termination-approval-test
  (fixtures/with-created check-changed-worker))

(deftest ^{:stratum 1} revoked-forward-grant-does-not-prevent-read-only-recovery-observation-test
  (fixtures/with-created check-revoked-forward-grant))

(comment ::no-pod-phase-absence-or-labels-as-termination-or-permission)
