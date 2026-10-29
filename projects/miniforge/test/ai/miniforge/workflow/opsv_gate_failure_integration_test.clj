;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-gate-failure-integration-test
  "A gate failure on an OPSV phase stops success claims, never the record of
   what happened: events for effects that ran are journaled, and evidence for
   an actuation whose gate failed is finalized as a failure, also on recovery."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixture]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.workflow.checkpoint-root-support :as checkpoint]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.isolation-support :as isolation]
            [ai.miniforge.workflow.opsv-lifecycle-support :as support]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} declared-intent
  {:type :operational-policy-synthesis :mode :recommend-only})

(defn- ^{:stratum 0} evidence-base [workflow-id]
  (get-in (fixture/configured {:execution/id workflow-id}) [:execution/opts :opsv/evidence-base]))

(defn- ^{:stratum 0} material-input []
  (dissoc (support/workflow-input) :opsv/evidence-refs
          :opsv/metric-snapshot-artifact-refs :opsv/policy-diff-artifact-refs))

(defn- ^{:stratum 0} gated-workflow
  "The shipped OPSV workflow with a denying :review-approved gate on one phase."
  [gated-phase]
  (update (:workflow (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true}))
          :workflow/pipeline
          (partial mapv #(cond-> % (= gated-phase (:phase %)) (assoc :gates [:review-approved])))))

(defn- ^{:stratum 0} publish-unless-kind
  "Interrupt publication of one OPSV material kind; publish everything else."
  [publish failing-kind directory record]
  (if (and failing-kind (= failing-kind (get-in record [:artifact/metadata :opsv/material-kind])))
    (throw (java.io.IOException. "publication interrupted"))
    (publish directory record)))

(defn- ^{:stratum 0} oversize-gate-failure-snapshots
  "The snapshot encoder's answer for a record past its 16 MiB bound, given to
   the terminal checkpoint and to any snapshot that carries the gate failure.
   The post-actuation snapshot taken when the actuation ran still encodes."
  [encode value]
  (if (or (= :terminal-checkpoint (get-in value [:artifact/metadata :opsv/material-kind]))
          (str/includes? (pr-str value) ":opsv/phase-failure"))
    (anomaly/anomaly :invalid-input "snapshot exceeds 16 MiB" {})
    (encode value)))

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

(defn- ^{:stratum 1} run-gated!
  "Run the shipped OPSV workflow with a denying gate on `gated-phase`."
  [root gated-phase {:keys [evidence? failing-kind oversize?]}]
  (let [workflow-id (random-uuid)
        stream (events/create-event-stream {:sinks []})
        options (cond-> (assoc (support/workflow-options stream root)
                               :workflow-id workflow-id
                               :workflow/intent declared-intent)
                  evidence? (assoc :opsv/artifact-directory (.getCanonicalPath (io/file root))
                                   :opsv/evidence-base (evidence-base workflow-id)))
        publish (partial publish-unless-kind artifact/publish! failing-kind)
        encode (if oversize?
                 (partial oversize-gate-failure-snapshots artifact/encode-snapshot)
                 artifact/encode-snapshot)
        completed (with-redefs [artifact/publish! publish
                                artifact/encode-snapshot encode]
                    ;; Without evidence the input carries its own evidence references.
                    (workflow/run-pipeline (gated-workflow gated-phase)
                                           (if evidence? (material-input) (support/workflow-input))
                                           options))]
    {:completed completed :options options :stream stream}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} denied-actuation-is-finalized-as-a-failure
  (doseq [[label run-options]
          {"evidence published in the run"
           {:evidence? true}
           "bundle publication interrupted, then recovered from the checkpoint"
           {:evidence? true :failing-kind :evidence-bundle}
           (str "failure material interrupted before finalization, and the terminal "
                "checkpoint and any snapshot carrying the gate failure past the 16 MiB "
                "bound: recovery restores the post-actuation snapshot")
           {:evidence? true :failing-kind :actuation-failure :oversize? true}}]
    (testing label
      (checkpoint/call-with-temp-checkpoint-root
       (fn [root]
         (let [run (run-gated! root :opsv/actuate run-options)
               completed (:completed run)
               outcome (recovered-outcome root run)]
           (is (= :failed (:execution/status completed)))
           (is (= :failed (get-in completed [:execution/phase-results :opsv/actuate :status])))
           (is (not (contains? completed :workflow/evidence-bundle-id)))
           (is (false? (:outcome/success outcome))
               "recovered evidence records a failed actuation, never a success")
           (is (contains? (event-types (:stream run)) :opsv.actuation/emitted)
               "the actuation that ran is journaled, by the run or by recovery")))))))

(deftest ^{:stratum 2} denied-actuation-without-evidence-journals-its-events
  (checkpoint/call-with-temp-checkpoint-root
   (fn [root]
     (let [run (run-gated! root :opsv/actuate {:evidence? false})]
       (is (= :failed (:execution/status (:completed run))))
       (is (= :failed (get-in run [:completed :execution/phase-results :opsv/actuate :status])))
       (is (contains? (event-types (:stream run)) :opsv.actuation/emitted)
           "without evidence, the actuation's events are still journaled")))))

(deftest ^{:stratum 2} denied-execution-still-journals-its-load
  (checkpoint/call-with-temp-checkpoint-root
   (fn [root]
     (let [run (run-gated! root :opsv/execute {:evidence? true})
           types (event-types (:stream run))]
       (is (= :failed (:execution/status (:completed run))))
       (is (contains? types :opsv.experiment/started) "the load that ran is journaled")
       (is (contains? types :opsv/load-step))
       (is (not (contains? types :opsv.actuation/emitted)))))))

(use-fixtures :once isolation/with-isolated-host)

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.opsv-gate-failure-integration-test))
