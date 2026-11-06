;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.provider-dispatch-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.messages :as msg]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} default-dispatch-preserves-one-provider-report-test
  (let [{:keys [calls report operation]} (fixtures/probe :succeeded)
        result (effect/call-with-provider-dispatch! fixtures/dispatch! operation)]
    (is (identical? report result))
    (is (= 1 @calls))))

(deftest ^{:stratum 0} custom-provider-diagnostics-use-the-shared-factory-test
  (let [messages (fixtures/provider-messages)
        {:keys [calls operation]} (fixtures/probe :succeeded)
        result (effect/call-with-provider-dispatch! (constantly nil) operation messages)]
    (is (= #{:dispatch/unconfirmed-message :dispatch/invalid-message} (set (keys messages))))
    (is (not= (msg/t :dispatch/unconfirmed) (:dispatch/unconfirmed-message messages)))
    (is (not= (msg/t :dispatch/invalid-input) (:dispatch/invalid-message messages)))
    (is (= :failed (:effect/outcome result)))
    (is (= (:dispatch/unconfirmed-message messages) (:effect/failure result)))
    (is (zero? @calls))))

(deftest ^{:stratum 0} malformed-callbacks-preserve-provider-diagnostics-test
  (let [messages (fixtures/provider-messages)]
    (doseq [dispatch [fixtures/zero-arguments fixtures/two-arguments]]
      (let [{:keys [calls operation]} (fixtures/probe :succeeded)
            result (effect/call-with-provider-dispatch! dispatch operation messages)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (= (:dispatch/invalid-message messages) (:anomaly/message result)))
        (is (zero? @calls))))))

(deftest ^{:stratum 0} repeated-callbacks-and-late-errors-cannot-replace-the-provider-report-test
  (doseq [dispatch [fixtures/twice! fixtures/after-result!]]
    (let [{:keys [calls report operation]} (fixtures/probe :succeeded)
          result (effect/call-with-provider-dispatch! dispatch operation)]
      (is (identical? report result))
      (is (= 1 @calls)))))

(deftest ^{:stratum 0} refusal-before-attempt-is-returned-without-calling-the-provider-test
  (doseq [refusal [(fixtures/refusal) (fixtures/report :failed)]]
    (let [{:keys [calls operation]} (fixtures/probe :succeeded)
          result (effect/call-with-provider-dispatch! (constantly refusal) operation)]
      (is (identical? refusal result))
      (is (zero? @calls)))))

(deftest ^{:stratum 0} dispatch-cannot-fabricate-an-unconfirmed-provider-outcome-test
  (doseq [returned [nil {} (fixtures/report :succeeded) (fixtures/report :unknown-outcome)]]
    (let [{:keys [calls operation]} (fixtures/probe :succeeded)
          result (effect/call-with-provider-dispatch! (constantly returned) operation)]
      (is (= :failed (:effect/outcome result)))
      (is (= (msg/t :dispatch/unconfirmed) (:effect/failure result)))
      (is (zero? @calls)))))

(deftest ^{:stratum 0} failures-before-and-after-provider-admission-have-distinct-outcomes-test
  (let [before (fixtures/probe :succeeded)
        after (fixtures/probe :succeeded fixtures/throwing-port!)
        refused (effect/call-with-provider-dispatch! fixtures/throwing-dispatch! (:operation before))
        uncertain (effect/call-with-provider-dispatch! fixtures/dispatch! (:operation after))]
    (is (= :failed (:effect/outcome refused)))
    (is (= :unknown-outcome (:effect/outcome uncertain)))
    (is (zero? @(:calls before)))
    (is (= 1 @(:calls after)))))

(deftest ^{:stratum 0} escaped-thunk-is-closed-when-dispatch-returns-test
  (let [{:keys [calls operation]} (fixtures/probe :succeeded)
        retained (atom nil)
        refusal (fixtures/refusal)
        dispatch (partial fixtures/retain! retained refusal)
        result (effect/call-with-provider-dispatch! dispatch operation)]
    (is (identical? refusal result))
    (is (nil? (@retained)))
    (is (zero? @calls))))

(deftest ^{:stratum 0} another-thread-cannot-attempt-the-provider-test
  (let [{:keys [calls operation]} (fixtures/probe :succeeded)
        result (effect/call-with-provider-dispatch! fixtures/threaded! operation)]
    (is (= :failed (:effect/outcome result)))
    (is (zero? @calls))))

(deftest ^{:stratum 0} malformed-callback-arities-refuse-before-the-provider-test
  (doseq [dispatch [fixtures/zero-arguments fixtures/two-arguments]]
    (let [{:keys [calls operation]} (fixtures/probe :succeeded)
          result (effect/call-with-provider-dispatch! dispatch operation)]
      (is (= :invalid-input (:anomaly/type result)))
      (is (zero? @calls)))))

(deftest ^{:stratum 0} invalid-boundary-inputs-never-invoke-the-provider-test
  (let [{:keys [calls operation]} (fixtures/probe :succeeded)
        messages (fixtures/messages)]
    (doseq [dispatch [nil false {}]]
      (is (= :invalid-input (:anomaly/type (effect/call-with-provider-dispatch! dispatch operation)))))
    (doseq [invalid [nil false {} (assoc messages :extra true)
                    (assoc messages :dispatch/unconfirmed-message " ")]]
      (is (= :invalid-input (:anomaly/type (effect/call-with-provider-dispatch! fixtures/dispatch! operation invalid)))))
    (is (= :invalid-input (:anomaly/type (effect/call-with-provider-dispatch! fixtures/dispatch! nil))))
    (is (zero? @calls))))

(deftest ^{:stratum 0} diagnostics-factory-validates-both-localized-messages-test
  (doseq [invalid [nil false " "]]
    (is (= :invalid-input (:anomaly/type (effect/provider-dispatch-diagnostics invalid (msg/t :dispatch/invalid-input)))))
    (is (= :invalid-input (:anomaly/type (effect/provider-dispatch-diagnostics (msg/t :dispatch/unconfirmed) invalid))))))

(deftest ^{:stratum 0} interruption-is-retained-without-leaking-the-test-thread-flag-test
  (let [{:keys [calls operation]} (fixtures/probe :succeeded)]
    (try
      (let [result (effect/call-with-provider-dispatch! fixtures/interrupting-dispatch! operation)
            interrupted? (.isInterrupted (Thread/currentThread))]
        (is (= :failed (:effect/outcome result)))
        (is interrupted?)
        (is (zero? @calls)))
      (finally (Thread/interrupted)))))

(comment ::same-fences-and-real-provider-report-semantics-as-the-existing-github-port)
