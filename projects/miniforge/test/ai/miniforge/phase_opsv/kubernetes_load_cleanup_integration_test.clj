;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as load]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-fixtures :as cleanup]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-fixtures :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-quiescence-fixtures :as quiescence]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.messages :as msg]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-canonical [transform {:keys [ctx request record pod runtime] :as fixture}]
  (let [observed (transform pod)
        metadata (get observed "metadata")
        [result calls] (cleanup/prepare fixture observed)
        payload (:kubernetes/request result)
        scope (:opsv/experiment result)]
    (is (= (:execution/id ctx) (:workflow-run/id result)))
    (is (= (:effect/id request) (:effect/id result)))
    (is (= :effect/load-test (:effect/class result)))
    (is (= :cleanup-load (:opsv/operation scope)))
    (is (m/validate grant/OpsvExperimentScope result))
    (is (= (hash/content-hash payload) (:opsv/payload-hash scope)))
    (is (= :delete (:method payload)))
    (is (= "application/json" (:media-type payload)))
    (is (= (str (get-in record [:effect/proposal :kubernetes/request :path]) "/" (get metadata "name")) (:path payload)))
    (is (= "v1" (get-in payload [:body "apiVersion"])))
    (is (= "DeleteOptions" (get-in payload [:body "kind"])))
    (is (= "Orphan" (get-in payload [:body "propagationPolicy"])))
    (is (= {"uid" (get metadata "uid") "resourceVersion" (get metadata "resourceVersion")}
            (get-in payload [:body "preconditions"])))
    (is (= (get metadata "uid") (get-in scope [:opsv/resources 0 :uid])))
    (is (= (get-in request [:recovery/input :recovery/duration-ms]) (:opsv/duration-ms scope)))
    (is (= record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (= [:get] (mapv :method calls)))
    (is (not-any? #(contains? result %) [:effect/grant-id :effect/envelope-id :load/quiescence :recovery/verified?]))))

(defn- ^{:stratum 0} check-refused-observation [transform {:keys [pod] :as fixture}]
  (let [[result calls] (cleanup/prepare fixture (transform pod))]
    (is (anomaly/anomaly? result))
    (is (= [:get] (mapv :method calls)))))

(defn- ^{:stratum 0} check-current-approval [{:keys [pod] :as fixture}]
  (let [changed (assoc-in fixture [:ctx :execution/opts :opsv/recovery-plan-admission
                                  :recovery/approved-plan-hashes] #{})
        [result calls] (cleanup/prepare changed pod)]
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-unknown-creation [{:keys [pod record] :as fixture}]
  (ownership/replace-record! fixture (assoc record :effect/state :unknown-outcome))
  (let [[result calls] (cleanup/prepare fixture pod)]
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-diagnostics [{:keys [pod record] :as fixture}]
  (let [[invalid invalid-calls] (cleanup/prepare (assoc-in fixture [:request :approved?] true) pod)
        [refused refused-calls] (cleanup/prepare (assoc-in fixture [:request :effect/id] (:effect/id record)) pod)]
    (is (= :invalid-input (:anomaly/type invalid)))
    (is (= :conflict (:anomaly/type refused)))
    (is (= (msg/ts :load/invalid-cleanup-request) (:anomaly/message invalid)))
    (is (= (msg/ts :load/cleanup-request-refused) (:anomaly/message refused)))
    (is (empty? invalid-calls))
    (is (= [:get] (mapv :method refused-calls)))))

(defn- ^{:stratum 0} check-fresh-revision [{:keys [pod] :as fixture}]
  (let [[original _] (cleanup/prepare fixture pod)
        [changed _] (cleanup/prepare fixture (assoc-in pod ["metadata" "resourceVersion"] "fresh-cleanup-revision"))]
    (is (= "fresh-cleanup-revision" (get-in changed [:kubernetes/request :body "preconditions" "resourceVersion"])))
    (is (not= (get-in original [:opsv/experiment :opsv/payload-hash])
              (get-in changed [:opsv/experiment :opsv/payload-hash])))))

(defn- ^{:stratum 0} check-changed-intent [{:keys [pod] :as fixture}]
  (doseq [path [[:load :limits :effect/id] [:load :comparison/artifact-ref]]]
    (let [changed (update-in fixture [:request :recovery/input] assoc-in path (random-uuid))
          [result calls] (cleanup/prepare changed pod)]
      (is (anomaly/anomaly? result))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-root-authority [{:keys [directory pod] :as fixture}]
  (let [[proposal calls] (cleanup/prepare fixture pod)
        breach-directory (str (fs/path directory "cleanup-breach-history"))
        issuance (assoc (load/issuance-request proposal) :workflow-run/status :cancelled)
        issued (grant/issue-for-effect breach-directory issuance clock/now)]
    (is (= [:get] (mapv :method calls)))
    (is (grant/valid? issued))
    (when (grant/valid? issued)
      (is (nil? (:grant/parent-id issued)))
      (is (false? (:grant/delegable? issued)))
      (is (grant/authorized? (grant/authorize issued (load/usage proposal) clock/now))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} never-started-owned-worker-produces-an-exact-read-only-cleanup-request-test
  (fixtures/with-created (partial check-canonical identity)))

(deftest ^{:stratum 1} physically-terminated-owned-worker-produces-an-exact-read-only-cleanup-request-test
  (fixtures/with-created (partial check-canonical quiescence/terminated-pod)))

(deftest ^{:stratum 1} running-unknown-incomplete-or-foreign-workers-cannot-be-cleaned-up-test
  (doseq [transform [#(update % "spec" dissoc "schedulingGates")
                     #(assoc-in % ["spec" "nodeName"] "scheduled-worker")
                     #(assoc-in % ["status" "phase"] "Failed")
                     #(assoc-in % ["spec" "containers"] [])
                     #(assoc-in % ["metadata" "uid"] "foreign-owned-name")]]
    (fixtures/with-created (partial check-refused-observation transform))))

(deftest ^{:stratum 1} revoked-current-recovery-approval-prevents-even-live-reads-test
  (fixtures/with-created check-current-approval))

(deftest ^{:stratum 1} uncertain-creation-is-not-adopted-by-name-for-cleanup-test
  (fixtures/with-created check-unknown-creation))

(deftest ^{:stratum 1} cleanup-schema-refusal-and-reused-effect-identity-have-distinct-localized-diagnostics-test
  (fixtures/with-created check-diagnostics))

(deftest ^{:stratum 1} fresh-provider-revision-changes-the-exact-cleanup-payload-hash-test
  (fixtures/with-created check-fresh-revision))

(deftest ^{:stratum 1} substituted-creation-or-comparison-intent-cannot-reuse-retained-approval-test
  (fixtures/with-created check-changed-intent))

(deftest ^{:stratum 1} generated-cleanup-scope-obtains-separate-real-root-recovery-authority-after-cancellation-test
  (fixtures/with-created check-root-authority))

(comment ::no-delete-dispatch-or-cleanup-success-is-claimed-by-these-read-only-constructor-tests)
