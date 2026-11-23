;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-creation-execution-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-load-creation-execution-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as native]
            [ai.miniforge.phase-opsv.kubernetes-load-request :as proposal]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as target]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-refused [transform reads? fixture]
  (let [[result calls] (fixtures/execute (transform fixture))]
    (is (anomaly/anomaly? result))
    (is (= reads? (boolean (seq calls))))
    (is (empty? (fixtures/posts calls)))
    (is (empty? (fs/glob (str (get-in fixture [:host/runtime :authority-directory]) "/grants") "*.grant.edn")))
    (is (empty? (forward/dispositions fixture)))))

(defn- ^{:stratum 0} substitute-next-intent [prepare change next? ctx input baseline]
  (let [intent (prepare ctx input baseline)]
    (if (compare-and-set! next? true false) (change intent) intent)))

(defn- ^{:stratum 0} check-replay [fixture]
  (let [[first-result _] (fixtures/execute fixture)
        [result calls] (fixtures/execute fixture)]
    (is (= :succeeded (:effect/state first-result)))
    (is (= :conflict (:anomaly/type result)))
    (is (= first-result (get-in result [:anomaly/data :effect/transaction])))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-unknown [{:keys [host/runtime] :as fixture}]
  (let [[result calls] (fixtures/execute-response fixture (wire/failed-future (java.io.IOException.)) (constantly nil))
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (= :unknown-outcome (:effect/state record)))
    (is (= record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (= 1 (count (fixtures/posts calls))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))
    (is (some? (:grant/revoked-at (grant/current (:authority-directory runtime) (:effect/grant-id record)))))))

(defn- ^{:stratum 0} check-success [{:keys [host/runtime] :as fixture}]
  (let [[result calls] (fixtures/execute fixture)
        request (first (fixtures/posts calls))
        audit (forward/dispositions fixture)
        assembly (forward/assembly fixture)]
    (is (= :succeeded (:effect/state result)))
    (is (= :granted (:effect/authority result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id result))))
    (is (= (get-in fixture [:input :load :limits :effect/id]) (:effect/id result)))
    (is (= native/created-uid (get-in result [:effect/observed :kubernetes/resource :uid])))
    (is (= 1 (count (fixtures/posts calls))))
    (is (= :post (:method (last calls))))
    (is (= fixtures/claimed-target-read-count (count (butlast calls))))
    (is (every? #(= :get (:method %)) (butlast calls)))
    (is (= (get-in result [:effect/proposal :kubernetes/request :body]) (json/parse-string (:body request))))
    (is (= 1 (count (get-in (json/parse-string (:body request)) ["spec" "schedulingGates"]))))
    (is (= [:proposed :committing :succeeded] (mapv :opsv/effect-state audit)))
    (is (every? (:opsv/event-refs assembly) (map :event/id audit)))
    (is (contains? (:opsv/grant-refs assembly) (:effect/grant-id result)))
    (is (not-any? #(contains? (:effect/observed result) %) [:load/started? :slo/passed? :recovery/verified?]))))

(defn- ^{:stratum 0} change-after-claim! [change fixture calls _options]
  (when (= fixtures/claimed-target-read-count (count calls))
    (case change
      :policy (forward/deny-policy! fixture)
      :stop (phase/stop-supervised-runs! (:host/supervisor fixture) clock/now)
      :expiry (reset! (:host/clock fixture) (.plusSeconds clock/now fixtures/beyond-grant-lifetime-seconds))
      :revoke (fixtures/revoke-current-grant! fixture))))

(defn- ^{:stratum 0} arm-constructor-fault! [next? _fixture calls _options]
  (when (= fixtures/initial-root-read-count (count calls)) (reset! next? true)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} disabled-unretained-foreign-and-serialized-authority-inputs-refuse-before-native-reads-test
  (doseq [transform [fixtures/disabled
                     #(assoc-in % [:request :recovery/artifact-ref] (random-uuid))
                     #(assoc-in % [:request :creation/provider :kubernetes/context] "foreign-context")
                     #(assoc-in % [:request :approved?] true)
                     #(update-in % [:request :recovery/input :load :runtime :load/cpu-millis] inc)]]
    (fixtures/with-creatable (partial check-refused transform false))))

(deftest ^{:stratum 1} foreign-physical-cluster-refuses-before-authority-and-creation-test
  (doseq [path [target/cluster-path inventory/namespace-path]]
    (fixtures/with-creatable (partial check-refused #(assoc-in % [:host/routes path "metadata" "uid"] "foreign-target") true))))

(deftest ^{:stratum 1} replay-requires-reconciliation-without-another-creation-test
  (fixtures/with-creatable check-replay))

(deftest ^{:stratum 1} uncertain-creation-is-durable-fenced-and-revoked-without-retry-test
  (fixtures/with-creatable check-unknown))

(defn- ^{:stratum 1} check-substituted-intent [change fixture]
  (let [prepare proposal/prepare execute fixtures/execute next? (atom false)
        on-read! (partial arm-constructor-fault! next?)]
    (with-redefs [proposal/prepare (partial substitute-next-intent prepare change next?)
                  fixtures/execute #(execute % on-read!)]
      (check-refused identity true fixture))))

(defn- ^{:stratum 1} check-late-refusal [change {:keys [host/runtime] :as fixture}]
  (let [[result calls] (fixtures/execute fixture (partial change-after-claim! change))]
    (is (anomaly/anomaly? result))
    (is (= :failed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (empty? (fixtures/posts calls)))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(deftest ^{:stratum 1} exact-inert-creation-has-current-governance-scoped-authority-and-three-durable-audit-dispositions-test
  (fixtures/with-creatable check-success))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} substituted-constructor-identity-or-payload-refuses-before-any-authority-or-disposition-test
  (doseq [change [#(assoc % :effect/id (random-uuid))
                  #(assoc-in % [:opsv/experiment :opsv/payload-hash] (hash/content-hash {:unplanned true}))]]
    (fixtures/with-creatable (partial check-substituted-intent change))))

(deftest ^{:stratum 2} changed-policy-stop-revocation-and-expiry-after-claim-refuse-before-the-first-native-post-test
  (doseq [change [:policy :stop :revoke :expiry]] (fixtures/with-creatable (partial check-late-refusal change))))

(comment
  (fixtures/with-creatable check-success))
