;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as authority]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration :as model]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replica]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failure-support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-canonical [{:keys [ctx input directory] :as fixture}]
  (let [routes (:restoration/routes fixture)
        [proposal calls] (fixtures/prepare fixture routes)
        scope (:opsv/experiment proposal)
        payload (:kubernetes/request proposal)
        current (get routes inventory/deployment-path)
        issuance (assoc (authority/issuance-request proposal) :workflow-run/status :cancelled)
        breach-directory (str (fs/path directory "restore-breach-history"))
        issued (grant/issue-for-effect breach-directory issuance clock/now)]
    (is (m/validate grant/OpsvExperimentScope proposal))
    (is (= (:execution/id ctx) (:workflow-run/id proposal)))
    (is (= (get-in fixture [:restoration/request :effect/id]) (:effect/id proposal)))
    (is (= :effect/deploy (:effect/class proposal)))
    (is (= :restore-baseline (:opsv/operation scope)))
    (is (= (:recovery/duration-ms input) (:opsv/duration-ms scope)))
    (is (= (hash/content-hash payload) (:opsv/payload-hash scope)))
    (is (= :patch (:method payload)))
    (is (= (get-in current ["metadata" "uid"]) (get-in payload [:body 0 "value"])))
    (is (= replica/fresh-revision (get-in payload [:body 1 "value"])))
    (is (= replica/original-count (get-in payload [:body 2 "value"])))
    (is (not= (get-in current ["spec" "replicas"]) (get-in payload [:body 2 "value"])))
    (is (seq calls))
    (is (every? #(= :get (:method %)) calls))
    (is (not-any? #(contains? proposal %) [:effect/grant-id :effect/envelope-id :recovery/verified?]))
    (is (grant/valid? issued))
    (when (grant/valid? issued)
      (is (nil? (:grant/parent-id issued)))
      (is (grant/authorized? (grant/authorize issued (authority/usage proposal) clock/now))))))

(defn- ^{:stratum 0} check-early-refusal [transform fixture]
  (let [changed (update fixture :restoration/request transform)
        [result calls] (fixtures/prepare changed (:restoration/routes fixture))]
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-current-approval [fixture]
  (let [changed (assoc-in fixture [:ctx :execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
        [result calls] (fixtures/prepare changed (:restoration/routes fixture))]
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-current-refusal [path value fixture]
  (let [routes (assoc-in (:restoration/routes fixture) path value)
        [result calls] (fixtures/prepare fixture routes)]
    (is (anomaly/anomaly? result))
    (is (seq calls))
    (is (every? #(= :get (:method %)) calls))))

(defn- ^{:stratum 0} check-fresh-revision [fixture]
  (let [routes (:restoration/routes fixture)
        [original _] (fixtures/prepare fixture routes)
        [fresh _] (fixtures/prepare fixture (assoc-in routes [inventory/deployment-path "metadata" "resourceVersion"] "later-restore-version"))]
    (is (= "later-restore-version" (get-in fresh [:kubernetes/request :body 1 "value"])))
    (is (not= (get-in original [:opsv/experiment :opsv/payload-hash]) (get-in fresh [:opsv/experiment :opsv/payload-hash])))
    (is (= replica/original-count (get-in fresh [:kubernetes/request :body 2 "value"])))))

(defn- ^{:stratum 0} fail-read! [calls failure _request]
  (swap! calls inc)
  (throw failure))

(defn- ^{:stratum 0} check-recovery-duration [{:keys [input prepared] :as fixture}]
  ;; Pure composition case: recovery and forward bounds can differ. This is
  ;; not a substitute for retained-plan admission or permission to execute.
  (let [forward-duration (get-in input [:load :limits :opsv/duration-ms])
        recovery-duration (inc forward-duration)
        plan (assoc (:recovery/plan prepared) :recovery/duration-ms recovery-duration)
        proposed (model/proposal-input (:restoration/request fixture) plan {})]
    (is (= recovery-duration (get-in proposed [:limits :opsv/duration-ms])))
    (is (not= forward-duration (get-in proposed [:limits :opsv/duration-ms])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-failed-read [interrupted? {:keys [ctx] :as fixture}]
  (let [calls (atom 0)
        failure (if interrupted? (InterruptedException.) (ex-info "Unavailable native read" {}))
        request (:restoration/request fixture)]
    (with-redefs [transport/start! (partial fail-read! calls failure)]
      (if interrupted?
        (let [[caught flagged?] (failure-support/interrupted-result
                                 (partial phase/prepare-kubernetes-baseline-restoration-proposal! ctx request))]
          (is (identical? failure caught))
          (is flagged?))
        (is (= :unavailable (:anomaly/type (phase/prepare-kubernetes-baseline-restoration-proposal! ctx request)))))
      (is (= 1 @calls)))))

(defn- ^{:stratum 1} check-distinct-identity [fixture]
  (let [creation-id (get-in fixture [:input :load :limits :effect/id])]
    (check-early-refusal #(assoc % :effect/id creation-id) fixture)))

(deftest ^{:stratum 1} retained-original-not-candidate-count-produces-exact-current-root-recovery-scope-test
  (fixtures/with-input check-canonical))

(deftest ^{:stratum 1} caller-count-capture-flags-and-foreign-provider-settings-refuse-before-http-test
  (doseq [transform [#(assoc % :replicas/count 99)
                     #(assoc % :current {})
                     #(assoc % :approved? true)
                     #(assoc-in % [:restoration/provider :kubernetes/context] "foreign-context")
                     #(assoc-in % [:restoration/provider :kubernetes/cluster-id] "foreign-cluster")
                     #(assoc-in % [:restoration/provider :kubernetes/namespace] "foreign-namespace")
                     #(assoc-in % [:restoration/provider :ignore-ssl?] true)]]
    (fixtures/with-input (partial check-early-refusal transform))))

(deftest ^{:stratum 1} revoked-recovery-readiness-prevents-all-restoration-capture-reads-test
  (fixtures/with-input check-current-approval))

(deftest ^{:stratum 1} fresh-provider-preconditions-and-identity-are-required-within-retained-count-bounds-test
  (doseq [[path value] [[[inventory/deployment-path "metadata" "uid"] "foreign-deployment-uid"]
                       [[inventory/deployment-path "spec" "replicas"] 99]
                       [[inventory/deployment-path "metadata" "resourceVersion"] ""]]]
    (fixtures/with-input (partial check-current-refusal path value))))

(deftest ^{:stratum 1} physical-cluster-identity-change-cannot-be-hidden-by-retaining-target-aliases-test
  (fixtures/with-input
    (partial check-current-refusal [native/cluster-path "metadata" "uid"] "foreign-cluster-root-uid")))

(deftest ^{:stratum 1} later-provider-revision-changes-restoration-hash-without-adopting-candidate-count-test
  (fixtures/with-input check-fresh-revision))

(deftest ^{:stratum 1} pure-restoration-composition-uses-independent-recovery-not-forward-duration-test
  (fixtures/with-input check-recovery-duration))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} native-read-failure-remains-an-anomaly-without-retry-or-effect-test
  (fixtures/with-input (partial check-failed-read false)))

(deftest ^{:stratum 2} interrupted-native-read-preserves-interruption-without-retry-or-effect-test
  (fixtures/with-input (partial check-failed-read true)))

(deftest ^{:stratum 2} creation-effect-identity-cannot-be-reused-as-restoration-effect-test
  (fixtures/with-input check-distinct-identity))

(comment
  (fixtures/with-input check-canonical))
