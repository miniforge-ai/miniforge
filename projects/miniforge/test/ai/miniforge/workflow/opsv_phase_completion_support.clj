;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-phase-completion-support
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as material]
            [ai.miniforge.phase-opsv.test-support :as native]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} completed-phase? [event]
  (= :workflow/phase-completed (:event/type event)))

(defn- ^{:stratum 0} publish-native-phase [stream ctx [phase-key :as handler]]
  (let [started (publishers/publish-phase-started! stream ctx phase-key
                                                 {:phase/expected-agent :agent/none})
        completed (material/step ctx handler)
        phase (:phase completed)
        receipt (publishers/publish-phase-completed! stream completed phase-key phase)]
    (is (= :workflow/phase-started (:event/type started)))
    (is (= :workflow/phase-completed (:event/type receipt)))
    (is (= (get-in phase [:result :artifacts]) (:phase/artifacts receipt)))
    (is (= (:duration-ms phase) (:phase/duration-ms receipt)))
    completed))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-native-events [ctx directory stream options]
  (let [configured (assoc (material/configured ctx) :event-stream stream)
        completed (reduce (partial publish-native-phase stream) configured native/handlers)
        history (events/get-events stream)
        completions (filterv completed-phase? history)
        ids (mapcat :phase/artifacts completions)
        bundle-id (get-in completed [:phase :result :output :opsv/evidence-artifact-id])]
    (is (= (mapv first native/handlers) (mapv :workflow/phase completions)))
    (is (= [] (:phase/artifacts (second completions))))
    (is (some #{bundle-id} (:phase/artifacts (last completions))))
    (doseq [id ids]
      (is (= id (:artifact/id (artifact/read-published directory id)))))
    (events/close-current-event-stream! stream)
    (is (= history (journal/reopened-events options)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-journal [ctx directory]
  (journal/with-stream (partial verify-native-events ctx directory)))
