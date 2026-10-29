;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-gate-failure-integration-test
  "A gate failure on an OPSV phase stops success claims, never the record of
   what happened: events for effects that ran are journaled, and evidence for
   an actuation whose gate failed is finalized as a failure, also on recovery."
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixture]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.workflow.checkpoint-root-support :as checkpoint]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.isolation-support :as isolation]
            [ai.miniforge.workflow.opsv-lifecycle-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} declared-intent
  {:type :operational-policy-synthesis :mode :recommend-only})

(defn- ^{:stratum 0} evidence-base [workflow-id]
  (get-in (fixture/configured {:execution/id workflow-id}) [:execution/opts :opsv/evidence-base]))

(defn- ^{:stratum 0} material-input []
  (dissoc (support/workflow-input) :opsv/evidence-refs
          :opsv/metric-snapshot-artifact-refs :opsv/policy-diff-artifact-refs))

(defn- ^{:stratum 0} gate-step [gated-phase step]
  (cond-> step (= gated-phase (:phase step)) (assoc :gates [:review-approved])))

(defn- ^{:stratum 0} publish-unless-bundle [publish fail? directory record]
  (if (and fail? (= :evidence-bundle (get-in record [:artifact/metadata :opsv/material-kind])))
    (throw (java.io.IOException. "publication interrupted"))
    (publish directory record)))

(defn- ^{:stratum 0} event-types [stream]
  (set (map :event/type (events/get-events stream))))

(defn- ^{:stratum 0} recovered-outcome
  "Resume evidence publication from the saved checkpoint, as an operator would."
  [root {:keys [options]}]
  (let [saved (workflow/load-checkpoint-data (:workflow-id options) {:checkpoint/root root})
        recovered (opsv/publish-finalized-evidence!
                   (assoc (:machine-snapshot saved) :execution/opts options))]
    (get-in recovered [:opsv/evidence-bundle :evidence/outcome])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} gated-workflow
  "The shipped OPSV workflow with a denying :review-approved gate on one phase."
  [gated-phase]
  (update (:workflow (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true}))
          :workflow/pipeline (partial mapv (partial gate-step gated-phase))))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} run-gated!
  [root gated-phase fail-bundle-publication?]
  (let [workflow-id (random-uuid)
        stream (events/create-event-stream {:sinks []})
        options (assoc (support/workflow-options stream root)
                       :workflow-id workflow-id
                       :workflow/intent declared-intent
                       :opsv/artifact-directory (.getCanonicalPath (io/file root))
                       :opsv/evidence-base (evidence-base workflow-id))
        publish (partial publish-unless-bundle artifact/publish! fail-bundle-publication?)
        completed (with-redefs [artifact/publish! publish]
                    (workflow/run-pipeline (gated-workflow gated-phase) (material-input) options))]
    {:completed completed :options options :stream stream}))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} verify-denied-actuation [fail-bundle-publication? root]
  (let [run (run-gated! root :opsv/actuate fail-bundle-publication?)
        completed (:completed run)]
    (is (= :failed (:execution/status completed)))
    (is (= :failed (get-in completed [:execution/phase-results :opsv/actuate :status])))
    (is (not (contains? completed :workflow/evidence-bundle-id)))
    (is (contains? (event-types (:stream run)) :opsv.actuation/emitted)
        "the actuation that ran is journaled")
    (is (false? (:outcome/success (recovered-outcome root run)))
        "recovered evidence records a failed actuation, never a success")))

(defn- ^{:stratum 3} verify-denied-execution [root]
  (let [run (run-gated! root :opsv/execute false)
        types (event-types (:stream run))]
    (is (= :failed (:execution/status (:completed run))))
    (is (contains? types :opsv.experiment/started) "the load that ran is journaled")
    (is (contains? types :opsv/load-step))
    (is (not (contains? types :opsv.actuation/emitted)))))

;------------------------------------------------------------------------------ Layer 4

(deftest ^{:stratum 4} denied-actuation-is-finalized-as-a-failure
  (testing "evidence published in the run"
    (checkpoint/call-with-temp-checkpoint-root (partial verify-denied-actuation false)))
  (testing "bundle publication interrupted, then recovered from the checkpoint"
    (checkpoint/call-with-temp-checkpoint-root (partial verify-denied-actuation true))))

(deftest ^{:stratum 4} denied-execution-still-journals-its-load
  (checkpoint/call-with-temp-checkpoint-root verify-denied-execution))

(use-fixtures :once isolation/with-isolated-host)

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.opsv-gate-failure-integration-test))
