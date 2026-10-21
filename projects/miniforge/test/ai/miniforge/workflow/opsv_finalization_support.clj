;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-finalization-support
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixture]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.evidence-reference-support :as reference]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.opsv-lifecycle-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private declared-intent
  {:type :operational-policy-synthesis :mode :recommend-only})

(defn- ^{:stratum 0} evidence-base [workflow-id]
  (get-in (fixture/configured {:execution/id workflow-id}) [:execution/opts :opsv/evidence-base]))

(defn- ^{:stratum 0} material-input []
  (dissoc (support/workflow-input) :opsv/evidence-refs
          :opsv/metric-snapshot-artifact-refs :opsv/policy-diff-artifact-refs))

(defn- ^{:stratum 0} publish-unless-bundle [publish fail-publication? directory record]
  (if (and fail-publication? (= :evidence-bundle (get-in record [:artifact/metadata :opsv/material-kind])))
    (throw (java.io.IOException. "publication interrupted"))
    (publish directory record)))

(defn- ^{:stratum 0} confirm-recovery! [root options completed expected-status]
  (let [directory (:opsv/artifact-directory options)
        workflow-id (:workflow-id options)
        saved (workflow/load-checkpoint-data workflow-id {:checkpoint/root root})
        snapshot (assoc (:machine-snapshot saved) :execution/opts options)
        assembly (get-in snapshot [:execution/input :opsv/evidence-assembly])
        recovered (opsv/publish-finalized-evidence! snapshot)
        bundle (:opsv/evidence-bundle recovered)
        metric-refs (get-in bundle [:evidence/opsv :opsv/metric-snapshot-artifact-refs])
        measurements (mapv (partial artifact/read-published directory) metric-refs)
        stored (artifact/read-published directory (:opsv/evidence-artifact-id recovered))]
    (is (= expected-status (:execution/status completed)))
    (if (= :completed expected-status)
      (reference/assert-completed completed snapshot (:artifact/id stored))
      (do (is (not (contains? completed :workflow/evidence-bundle-id)))
          (is (not (contains? snapshot :workflow/evidence-bundle-id)))))
    (is (= workflow-id (:execution/id completed) (:evidence-bundle/workflow-id bundle)))
    (is (true? (:valid? (evidence/validate-canonical-bundle bundle))))
    (is (= bundle (:artifact/content stored)))
    (is (= :finalized (:opsv.assembly/status assembly)))
    (is (= bundle (get-in completed [:execution/input :opsv/evidence-assembly :opsv.assembly/bundle])))
    (is (= recovered (opsv/publish-finalized-evidence! snapshot)))
    (is (= 8 (count (get-in bundle [:evidence/opsv :opsv/artifact-refs]))))
    (is (= 2 (count metric-refs)))
    (is (= #{:metric-snapshot :verification-measurements}
           (set (map #(get-in % [:artifact/metadata :opsv/material-kind]) measurements))))
    (is (true? (get-in bundle [:evidence/outcome :outcome/success])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-and-confirm! [fail-publication? root stream]
  (let [workflow-id (random-uuid)
        options (assoc (support/workflow-options stream root)
                       :workflow-id workflow-id
                       :workflow/intent declared-intent
                       :opsv/artifact-directory (.getCanonicalPath (io/file root))
                       :opsv/evidence-base (evidence-base workflow-id))
        config (:workflow (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true}))
        publish (partial publish-unless-bundle artifact/publish! fail-publication?)
        completed (with-redefs [artifact/publish! publish]
                    (workflow/run-pipeline config (material-input) options))]
    (confirm-recovery! root options completed (if fail-publication? :failed :completed))
    completed))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-legacy! [fail-publication? root]
  (run-and-confirm! fail-publication? root (events/create-event-stream {:sinks []})))

(defn ^{:stratum 2} run-current! [root stream options]
  (let [completed (run-and-confirm! false root stream)
        history (events/get-events stream)
        started (support/event-of-type history :workflow/started)
        terminal (support/event-of-type history :workflow/completed)
        phases (filter #(= :workflow/phase-completed (:event/type %)) history)]
    (is (= :completed (:execution/status completed)))
    (is (= declared-intent (:workflow/intent started)))
    (is (= (mapv :phase support/expected-pipeline) (mapv :workflow/phase phases)))
    (is (= (:workflow/evidence-bundle-id completed) (:workflow/evidence-bundle-id terminal)))
    (is (every? #(= (:execution/id completed) (:workflow/id %)) history))
    (is (= (range (count history)) (map :event/sequence-number history)))
    (events/close-current-event-stream! stream)
    (is (= history (journal/reopened-events options)))))

(comment ::full-run-finalization-fixtures)
