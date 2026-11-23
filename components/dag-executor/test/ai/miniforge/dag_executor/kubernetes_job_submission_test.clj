;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-job-submission-test
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-creation :as creation]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-submission :as submission]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as replies]
            [ai.miniforge.dag-executor.kubernetes-submission-fixtures :as fixtures]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} rejected-reasons
  "Transport failures stay closed diagnostics even if a fake supplies secret output."
  [[:deadline :deadline] [:input-error :input-error] [:incomplete-output :incomplete-output]
   [:output-limit :output-limit] [:command-io-error :command-io-failed]
   [:command-error :command-failed] [:untrusted-provider-text :command-failed]])

(defn- ^{:stratum 0} reject-io! [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} expire-encoding! [expired encoder & arguments]
  (reset! expired true)
  (apply encoder arguments))

(defn- ^{:stratum 0} start-budget! [encoded? after-ms budget-ms]
  (is (false? @encoded?))
  (after-ms budget-ms))

(defn- ^{:stratum 0} changed-admission! [change! parser & arguments]
  (change!)
  (apply parser arguments))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-expired-or-cancelled-submission-does-not-encode-or-dispatch
  (with-redefs [json/generate-string reject-io! command/run! reject-io!]
    (is (= :deadline (:reason (submission/submit! (System/nanoTime) nil nil fixtures/budget-ms))))
    (try+
      (.interrupt (Thread/currentThread))
      (let [reply (submission/submit! (deadline/after-ms fixtures/budget-ms) nil nil fixtures/budget-ms)
            interrupted? (Thread/interrupted)]
        (is (= :interrupted (:reason reply)))
        (is interrupted?))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} test-encoding-consumes-the-original-creation-deadline
  (let [expired (atom false)
        encoder json/generate-string
        after-ms deadline/after-ms
        job (fixtures/job "λ")]
    (with-redefs [deadline/expired? (fn [_] @expired)
                  deadline/after-ms (partial start-budget! expired after-ms)
                  json/generate-string (partial expire-encoding! expired encoder)]
      (with-redefs-fn {#'command/start! reject-io!}
        #(is (= :deadline (get-in (creation/create! "unused" job fixtures/budget-ms)
                                  [:error :data :reason])))))))

(deftest ^{:stratum 1} test-transport-diagnostics-never-admit-output-or-retain-secrets
  (let [job (fixtures/job "λ")]
    (doseq [[reason expected] rejected-reasons
            :let [reply (assoc (replies/provider-reply 0 "secret-provider-output") :reason reason)]]
      (with-redefs [command/run! (constantly reply)]
        (is (= {:reason expected} (get-in (creation/create! "unused" job fixtures/budget-ms)
                                         [:error :data])))))))

(deftest ^{:stratum 1} test-expiry-or-cancellation-during-receipt-parsing-refuses-admission
  (let [job (fixtures/job "λ")
        parser json/parse-string-strict
        reply (replies/provider-reply 0 (json/generate-string (replies/created-job job)))]
    (doseq [cancelled? [false true]]
      (let [expired (atom false)
            change! (if cancelled? #(.interrupt (Thread/currentThread)) (partial reset! expired true))]
        (try+
          (with-redefs [deadline/expired? (fn [_] @expired)
                        command/run! (constantly reply)
                        json/parse-string-strict (partial changed-admission! change! parser)]
            (let [reply (creation/create! "unused" job fixtures/budget-ms)
                  interrupted? (Thread/interrupted)]
              (is (= (if cancelled? :interrupted :deadline) (get-in reply [:error :data :reason])))
              (is (= cancelled? interrupted?))))
          (finally (Thread/interrupted)))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-job-submission-test))
