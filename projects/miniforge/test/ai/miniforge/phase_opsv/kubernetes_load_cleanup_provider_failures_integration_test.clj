;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup-provider-failures-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-provider-fixtures :as stop]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-unconfirmed [response status fixture]
  (let [[result calls] (fixtures/execute fixture (response fixture) status)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= 1 (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-dispatch [callback expected-success? expected-mutations fixture]
  (let [pending (wire/completed (wire/response (json/generate-string (:pod fixture))))
        [result calls] (fixtures/execute-response fixture pending callback)]
    (is (= expected-success? (= :succeeded (:effect/state result))))
    (is (= expected-mutations (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-timeout [{:keys [runtime] :as fixture}]
  (let [pending (CompletableFuture.)
        [result calls] (fixtures/execute-response fixture pending recovery/timeout-dispatch!)
        [retried retry-calls] (fixtures/execute fixture)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (.isCancelled pending))
    (is (not= :succeeded (:effect/state retried)))
    (is (empty? retry-calls))))

(defn- ^{:stratum 0} check-current-metadata [key value fixture]
  (let [[result calls] (fixtures/execute (assoc-in fixture [:pod "metadata" key] value))]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? (fixtures/mutations calls)))))

(defn- ^{:stratum 0} check-stop-status [fixture]
  (let [pending (wire/completed (wire/response (json/generate-string (stop/response (:pod fixture)))
                                               {:status transport/accepted-status}))
        [result calls] (stop/execute-response fixture pending dispatch/dispatch!)]
    (is (= :unknown-outcome (:effect/state result)))
    (is (= 1 (count (stop/mutations calls))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} foreign-or-incomplete-native-deletion-acknowledgements-remain-uncertain-test
  (doseq [response [#(assoc-in (fixtures/pod-response %) ["metadata" "uid"] "foreign-uid")
                    #(assoc-in (fixtures/pod-response %) ["metadata" "name"] "foreign-name")
                    #(assoc-in (fixtures/pod-response %) ["metadata" "namespace"] "foreign-namespace")
                    #(update (fixtures/pod-response %) "metadata" dissoc "resourceVersion")
                    #(update (fixtures/status-response %) "details" dissoc "uid")
                    #(assoc-in (fixtures/status-response %) ["details" "uid"] "foreign-uid")
                    #(assoc-in (fixtures/status-response %) ["details" "name"] "foreign-name")
                    #(assoc-in (fixtures/status-response %) ["details" "group"] "apps")
                    #(assoc-in (fixtures/status-response %) ["details" "kind"] "Pod")
                    #(assoc (fixtures/status-response %) "status" "Failure")]]
    (fixtures/with-proposed (partial check-unconfirmed response transport/success-status))))

(deftest ^{:stratum 1} deletion-status-policy-does-not-accept-unlisted-2xx-or-absence-test
  (doseq [status [transport/created-status transport/not-found-status]]
    (fixtures/with-proposed (partial check-unconfirmed fixtures/pod-response status))))

(deftest ^{:stratum 1} deletion-status-policy-does-not-broaden-existing-stop-patch-success-test
  (stop/with-proposed check-stop-status))

(deftest ^{:stratum 1} native-cleanup-callbacks-cannot-repeat-fabricate-or-cross-thread-the-effect-test
  (doseq [[callback expected? mutations] [[dispatch/twice! true 1] [dispatch/after-result! true 1]
                                          [nil false 0]
                                          [(constantly (dispatch/report :succeeded)) false 0]]]
    (fixtures/with-proposed (partial check-dispatch callback expected? mutations))))

(deftest ^{:stratum 1} native-cleanup-cannot-dispatch-the-effect-from-another-thread-test
  (fixtures/with-proposed (partial check-dispatch dispatch/threaded! false 0)))

(deftest ^{:stratum 1} uncertain-delete-cancels-its-owned-future-and-cannot-be-retried-test
  (fixtures/with-proposed check-timeout))

(deftest ^{:stratum 1} current-uid-and-resource-version-are-reconstructed-before-deletion-test
  (doseq [[key value] [["uid" "foreign-live-uid"] ["resourceVersion" "changed-live-revision"]]]
    (fixtures/with-proposed (partial check-current-metadata key value))))

(comment
  (fixtures/with-proposed check-timeout))
