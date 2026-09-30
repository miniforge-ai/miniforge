;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-checkpoint-integration-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.checkpoint-root-support :as checkpoint]
            [ai.miniforge.workflow.isolation-support :as isolation]
            [ai.miniforge.workflow.opsv-lifecycle-support :as support]
            [clojure.test :refer [deftest is use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} typed-assembly [workflow-id]
  (let [store (evidence/create-opsv-assembly-store)
        assembly (evidence/allocate-opsv-assembly! store workflow-id)]
    (assoc assembly :test/typed-values
           {:set #{:one :two}
            :list '(1 2 3)
            :timestamp (java.time.Instant/parse "2026-09-30T00:00:00Z")})))

(defn- ^{:stratum 0} discovery-workflow []
  (let [loaded (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true})]
    (assoc (:workflow loaded) :workflow/pipeline [{:phase :opsv/discover} {:phase :done}])))

(defn- ^{:stratum 0} restore-from-disk [workflow-id root options]
  (let [saved (workflow/load-checkpoint-data workflow-id {:checkpoint/root root})
        snapshot (:machine-snapshot saved)
        fresh-options (dissoc options :opsv/evidence-assembly-store)
        detached (dissoc snapshot :opsv/evidence-assembly-store)
        resumed (assoc detached :execution/opts fresh-options)
        interceptor (phase/get-phase-interceptor {:phase :opsv/discover})]
    ;; The display map is deliberately unavailable; only the encoded snapshot can restore it.
    ((:enter interceptor) (assoc-in resumed [:execution/input :opsv/evidence-assembly] {}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-disk-round-trip [root]
  (let [workflow-id (random-uuid)
        assembly (typed-assembly workflow-id)
        bundle-id (:evidence-bundle/id assembly)
        store (evidence/restore-opsv-assembly-store assembly)
        stream (events/create-event-stream {:sinks []})
        options (assoc (support/workflow-options stream root)
                       :workflow-id workflow-id :opsv/evidence-assembly-store store)
        input (assoc (support/workflow-input) :opsv/evidence-bundle-id bundle-id)
        completed (workflow/run-pipeline (discovery-workflow) input options)
        restored (restore-from-disk workflow-id root options)
        actual (evidence/get-opsv-assembly (:opsv/evidence-assembly-store restored) bundle-id)]
    (is (= :completed (:execution/status completed)))
    (is (string? (get-in completed [:execution/input :opsv/evidence-snapshot])))
    (is (= assembly actual))
    (is (set? (:opsv/event-refs actual)))
    (is (set? (get-in actual [:test/typed-values :set])))
    (is (list? (get-in actual [:test/typed-values :list])))
    (is (instance? java.time.Instant (get-in actual [:test/typed-values :timestamp])))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} workflow-checkpoint-restores-exact-assembly-without-runtime-store
  (checkpoint/call-with-temp-checkpoint-root assert-disk-round-trip))

(use-fixtures :once isolation/with-isolated-host)

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.opsv-checkpoint-integration-test))
