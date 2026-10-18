;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.gate-contract-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.event-stream.interface :as events]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} evidence-drafts [stream workflow-id]
  (let [envelope (decision/envelope [] [] {:pins/pack-revision nil :pins/rule-ids [:fixture/rule] :pins/event-watermark nil})
        options {:envelope-id (:envelope/id envelope)}]
    [(events/phase-decision stream workflow-id :opsv/actuate envelope)
     (events/gate-rule-applied stream workflow-id :opsv/actuate :fixture/rule :passed options)]))

(defn- ^{:stratum 0} reopen-and-verify [options scope drafts receipts]
  (let [stream (events/create-current-event-stream options)]
    (is (not (anomaly/anomaly? stream)) (pr-str stream))
    (try+
      (is (= receipts (events/get-events stream {:scope scope})))
      (is (= receipts (mapv (partial events/publish! stream) drafts)))
      (is (= receipts (events/get-events stream {:scope scope})))
      (finally (events/close-current-event-stream! stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-journal [options]
  (let [stream (events/create-current-event-stream options)
        workflow-id (random-uuid)
        drafts (evidence-drafts stream workflow-id)
        scope [:workflow workflow-id]]
    (is (not (anomaly/anomaly? stream)) (pr-str stream))
    (try+
      (is (anomaly/anomaly? (events/publish! stream (dissoc (first drafts) :envelope/id))))
      (let [receipts (mapv (partial events/publish! stream) drafts)]
        (is (= [0 1] (mapv :event/sequence-number receipts)))
        (is (= receipts (events/get-events stream {:scope scope})))
        (is (= drafts (mapv #(dissoc % :event/sequence-number) receipts)))
        (is (anomaly/anomaly? (events/publish! stream (assoc (first drafts) :gate/phase :review))))
        (let [foreign (first (evidence-drafts stream (random-uuid)))
              receipt (events/publish! stream foreign)]
          (is (= 0 (:event/sequence-number receipt)))
          (is (= receipts (events/get-events stream {:scope scope}))))
        (events/close-current-event-stream! stream)
        (is (anomaly/anomaly? (events/publish! stream (first drafts))))
        (reopen-and-verify options scope drafts receipts))
      (finally (events/close-current-event-stream! stream)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} acknowledged-gate-evidence-survives-restart-and-retry-unchanged
  (fs/with-temp-dir [directory {:prefix "gate-current-contract-"}]
    (let [path (str (fs/real-path directory))]
      (verify-journal {:journal-directory path}))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.gate-contract-integration-test))
