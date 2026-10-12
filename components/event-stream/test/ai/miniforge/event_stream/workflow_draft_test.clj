;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-draft-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as fixture]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused? [draft]
  (anomaly/anomaly? (events/prepare-current-publication draft)))

(deftest ^{:stratum 0} provenance-is-not-silently-redacted
  (doseq [[event-type path] [[:workflow/started [:workflow/spec :source]]
                            [:workflow/started [:workflow/intent :source]]
                            [:workflow/cancelled [:cancel/requested-by]]]]
    (let [draft (assoc-in (fixture/draft (random-uuid) event-type) path "AKIAIOSFODNN7EXAMPLE")
          result (events/prepare-current-publication draft)]
      (is (anomaly/anomaly? result))
      (is (redaction/clean? result)))))

(deftest ^{:stratum 0} context-and-failure-observations-remain-redactable
  (doseq [[event-type path] [[:workflow/phase-started [:phase/context :password]]
                            [:workflow/failed [:workflow/error-details :password]]]]
    (let [draft (assoc-in (fixture/draft (random-uuid) event-type) path "fixture-password")
          result (events/prepare-current-publication draft)]
      (is (vector? result))
      (is (= (redaction/marker) (get-in (second result) path))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} all-canonical-profiles-are-scoped-and-require-their-fields
  (doseq [[event-type payload] fixture/payloads]
    (let [workflow-id (random-uuid)
          draft (fixture/draft workflow-id event-type)]
      (is (= [[:workflow workflow-id] draft] (events/prepare-current-publication draft)))
      (doseq [field (concat (keys payload) [:workflow/id :scope/type :event/version])]
        (is (refused? (dissoc draft field))))
      (doseq [changed [{:event/sequence-number 0} {:event/version "2.0.0"}
                       {:scope/type :chain} {:workflow/id nil} {:timestamp nil}]]
        (is (refused? (merge draft changed)))))))

(deftest ^{:stratum 1} terminal-types-cannot-carry-a-competing-status
  (doseq [event-type [:workflow/completed :workflow/failed :workflow/cancelled]
          status [:success :failure :cancelled nil]]
    (is (refused? (assoc (fixture/draft (random-uuid) event-type) :workflow/status status)))))

(deftest ^{:stratum 1} phase-refusal-and-redirection-require-typed-details
  (let [draft (fixture/draft (random-uuid) :workflow/phase-completed)
        blocked (assoc draft :phase/outcome :blocked)
        redirected (assoc draft :phase/outcome :redirected)]
    (is (refused? blocked))
    (is (not (refused? (assoc blocked :phase/blocked-reason :quality-gate))))
    (is (refused? (assoc blocked :phase/blocked-reason :unknown)))
    (is (refused? redirected))
    (is (not (refused? (assoc redirected :phase/transition-request
                             {:transition/type :redirect :transition/target :plan}))))
    (is (refused? (assoc draft :phase/outcome :unknown)))
    (is (refused? (assoc draft :phase/duration-ms -1)))))

(deftest ^{:stratum 1} cancellation-links-control-actions-and-failure-uses-canonical-taxonomy
  (let [cancelled (fixture/draft (random-uuid) :workflow/cancelled)
        controlled (assoc cancelled :cancel/source :control-action)
        failed (fixture/draft (random-uuid) :workflow/failed)]
    (is (refused? controlled))
    (is (not (refused? (assoc controlled :action/id (random-uuid)))))
    (is (refused? (assoc cancelled :cancel/source :unknown)))
    (is (refused? (assoc failed :failure/class :unknown)))))

(comment
  ::canonical-workflow-admission)
