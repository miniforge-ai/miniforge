;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-finalization-integration-test
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.checkpoint-root-support :as checkpoint]
            [ai.miniforge.workflow.isolation-support :as isolation]
            [ai.miniforge.workflow.opsv-lifecycle-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} evidence-base [workflow-id]
  {:evidence-bundle/workflow-id workflow-id
   :evidence-bundle/created-at #inst "2026-09-29T00:00:00Z"
   :evidence-bundle/version "1.0.0"
   :evidence/intent {:intent/type :update
                     :intent/description "Evaluate staging scaling."
                     :intent/business-reason "Meet the declared service objectives."
                     :intent/constraints []
                     :intent/declared-at #inst "2026-09-29T00:00:00Z"}})

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

(defn- ^{:stratum 1} run-and-confirm! [fail-publication? root]
  (let [workflow-id (random-uuid)
        stream (events/create-event-stream {:sinks []})
        options (assoc (support/workflow-options stream root)
                       :workflow-id workflow-id
                       :opsv/artifact-directory (.getCanonicalPath (io/file root))
                       :opsv/evidence-base (evidence-base workflow-id))
        config (:workflow (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true}))
        publish (partial publish-unless-bundle artifact/publish! fail-publication?)
        completed (with-redefs [artifact/publish! publish]
                    (workflow/run-pipeline config (material-input) options))]
    (confirm-recovery! root options completed (if fail-publication? :failed :completed))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} shared-workflow-runner-persists-finalized-opsv-evidence-test
  (checkpoint/call-with-temp-checkpoint-root (partial run-and-confirm! false)))

(deftest ^{:stratum 2} failed-publication-recovers-from-shared-workflow-checkpoint-test
  (checkpoint/call-with-temp-checkpoint-root (partial run-and-confirm! true)))

(use-fixtures :once isolation/with-isolated-host)

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.opsv-finalization-integration-test))
