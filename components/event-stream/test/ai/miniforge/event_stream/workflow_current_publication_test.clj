;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-current-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as fixture]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} successful-run [workflow-id]
  (mapv (partial fixture/draft workflow-id)
        [:workflow/started :workflow/phase-started :workflow/phase-completed :workflow/completed]))

(defn- ^{:stratum 0} verify-reopened [options scope expected]
  (let [stream (events/create-current-event-stream options)]
    (is (not (anomaly/anomaly? stream)) (pr-str stream))
    (when-not (anomaly/anomaly? stream)
      (try+ (is (= expected (events/get-events stream {:scope scope})))
        (finally (events/close-current-event-stream! stream))))))

(defn- ^{:stratum 0} verify-refusals [stream _]
  (doseq [[event-type payload] fixture/payloads]
    (let [draft (fixture/draft (random-uuid) event-type)]
      (doseq [field (keys payload)]
        (is (anomaly/anomaly? (events/publish! stream (dissoc draft field)))))))
  (is (empty? (events/get-events stream))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-agentless-phase [stream options]
  (let [workflow-id (random-uuid)
        draft (assoc (fixture/draft workflow-id :workflow/phase-started)
                     :workflow/phase :opsv/plan :phase/expected-agent :agent/none)]
    (is (anomaly/anomaly? (events/publish! stream (assoc draft :phase/expected-agent nil))))
    (is (empty? (events/get-events stream)))
    (let [receipt (events/publish! stream draft)]
      (is (= :agent/none (:phase/expected-agent receipt)))
      (is (= 0 (:event/sequence-number receipt)))
      (is (anomaly/anomaly? (events/publish! stream (assoc draft :phase/expected-agent :implementer))))
      (is (= receipt (events/publish! stream draft)))
      (events/close-current-event-stream! stream)
      (verify-reopened options [:workflow workflow-id] [receipt]))))

(defn- ^{:stratum 1} verify-durability [stream options]
  (let [workflow-id (random-uuid)
        drafts (successful-run workflow-id)
        receipts (mapv (partial events/publish! stream) drafts)
        first-draft (first drafts)
        later-retry (assoc first-draft :event/timestamp (java.util.Date. 0))]
    (is (= [0 1 2 3] (mapv :event/sequence-number receipts)))
    (is (= receipts (events/get-events stream {:scope [:workflow workflow-id]})))
    (is (= (first receipts) (events/publish! stream later-retry)))
    (is (anomaly/anomaly? (events/publish! stream (assoc first-draft :message "different occurrence"))))
    (doseq [event-type [:workflow/failed :workflow/cancelled]]
      (let [receipt (events/publish! stream (fixture/draft (random-uuid) event-type))]
        (is (= event-type (:event/type receipt)))
        (is (= 0 (:event/sequence-number receipt)))))
    (events/close-current-event-stream! stream)
    (is (anomaly/anomaly? (events/publish! stream later-retry)))
    (verify-reopened options [:workflow workflow-id] receipts)))

(deftest ^{:stratum 1} malformed-profiles-never-enter-the-journal
  (fixture/with-stream verify-refusals))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} agentless-identity-survives-durable-replay
  (fixture/with-stream verify-agentless-phase))

(deftest ^{:stratum 2} lifecycle-receipts-have-durable-scoped-positions
  (fixture/with-stream verify-durability))

(comment
  ::durable-workflow-profiles)
