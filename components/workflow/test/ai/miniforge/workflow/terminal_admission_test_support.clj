;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.terminal-admission-test-support
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.runner :as pipeline]
            [ai.miniforge.workflow.runner-events :as publishers]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.workflow-start-test-support :as runner]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute [stream opts calls]
  ;; The shared legacy phase fixture lacks current phase-completion facts.
  ;; Isolate that earlier boundary; terminal construction and journaling stay real.
  (with-redefs [publishers/publish-phase-completed! (constantly nil)]
    (runner/execute stream opts calls)))

(defn- ^{:stratum 0} with-evidence [extract ctx]
  (assoc (extract ctx) :workflow/evidence-bundle-id (random-uuid)))

(defn- ^{:stratum 0} count-failure-attempt [attempts & _args]
  (swap! attempts inc)
  (runner/throw-construction))

(defn- ^{:stratum 0} assert-outcome [result status calls]
  (let [{:keys [machine-snapshot phase-results]}
        (checkpoints/load-checkpoint-data (:execution/id result) {})
        restored (context/restore-context (:execution/workflow result) {}
                                          machine-snapshot phase-results {})]
    (is (= status (:execution/status result) (:execution/status restored)))
    (is (= status (get-in result [:execution/output :status])))
    (is (= (:execution/id result) (:execution/id restored)))
    (is (= (:execution/phase-results result) (:execution/phase-results restored)))
    (is (= [:phase-enter :cleanup] @calls))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} verify-refusal [stream _options]
  (let [calls (atom [])
        result (execute stream {} calls)
        error (last (:execution/errors result))]
    (assert-outcome result :failed calls)
    (is (= :workflow-terminal-publication-failed (:type error)))
    (is (anomaly/any-anomaly? (:anomaly error)))
    (is (= :workflow/failed (:event/type (last (events/get-events stream)))))
    (is (not-any? #(= :workflow/completed (:event/type %)) (events/get-events stream)))))

(defn ^{:stratum 1} verify-accepted [stream _options]
  (let [calls (atom [])
        extract outcome/extract-output]
    (with-redefs [pipeline/extract-output (partial with-evidence extract)]
      (assert-outcome (execute stream {} calls) :completed calls))
    (is (= :workflow/completed (:event/type (last (events/get-events stream)))))))

(defn ^{:stratum 1} verify-fatal [stream _options]
  (let [calls (atom [])]
    (with-redefs [events/workflow-completed runner/throw-fatal]
      (is (thrown? AssertionError (execute stream {} calls))))
    (is (= [:phase-enter :cleanup] @calls))))

(defn ^{:stratum 1} verify-skipped [stream _options]
  (let [calls (atom [])]
    (assert-outcome (execute stream {:skip-lifecycle-events true} calls)
                    :completed calls)
    (is (not-any? #(= :workflow/completed (:event/type %)) (events/get-events stream)))))

(defn ^{:stratum 1} verify-double-refusal [stream _options]
  (let [calls (atom [])
        attempts (atom 0)]
    (with-redefs [events/workflow-failed (partial count-failure-attempt attempts)]
      (let [result (execute stream {} calls)]
        (assert-outcome result :failed calls)
        (is (= :workflow-failure-publication-failed
               (:type (last (:execution/errors result)))))
        (is (= 1 @attempts))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-constructor-refusal [stream options]
  (with-redefs [events/workflow-completed runner/throw-construction]
    (verify-refusal stream options)))

(comment ::runner-terminal-acceptance)
