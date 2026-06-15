;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.event-stream.routing-trigger-schema-test
  "Schema conformance + constructor round-trip tests for the six N5-delta-4
   routing-trigger, automation-edge, and supervisory-intervention event types:

     Routing triggers (N5-delta-4 §4.2):
       :pr-monitor/review-comments-arrived   — PrMonitorReviewCommentsArrived
       :pr-monitor/ci-failed                 — PrMonitorCiFailed
       :standards-review/posted              — StandardsReviewPosted

     Automation-edge correlator (N5-delta-4 §4.1):
       :supervisory/automation-edge-upserted — AutomationEdgeUpserted
         (schema-only; no public constructor)

     Supervisory interventions:
       :supervisory/intervention-requested   — InterventionRequested
       :supervisory/intervention-state-changed — InterventionStateChanged

   Coverage per schema (Rule 11 — test parity):
     1. A conforming map passes malli/validate.
     2. A map missing each required domain field fails malli/validate.
     3. Optional fields may be absent without failing validation.
     4. Optional fields are accepted when present.
     5. Constructor output has correct :event/type (keyword), uuid :event/id,
        inst :event/timestamp, and all domain-specific fields from args.
     6. Constructor output validates against its schema (round-trip).

   Routing-trigger constructors emit :workflow/id nil — routing events are
   PR-scoped, not workflow-scoped; the schema accepts nil via [:maybe uuid?].

   Tests exercise the public interface (core/) per Rule 13 (Polylith):
   constructors are consumed through the component boundary."
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Named constants — no magic literals in test bodies

(def ^:private sample-repo           "miniforge-ai/miniforge")
(def ^:private sample-pr-number      42)
(def ^:private sample-comments-count 3)
(def ^:private sample-check-name     "CI / build-and-test")
(def ^:private sample-conclusion     :failure)
(def ^:private sample-severity       :advisory)
(def ^:private blocking-severity     :blocking)
(def ^:private event-version         "1.0.0")
(def ^:private pause-type            :pause)
(def ^:private target-type           :workflow)
(def ^:private request-source        :tui)
(def ^:private proposed-state        :proposed)
(def ^:private applied-state         :applied)
(def ^:private dispatched-state      :dispatched)
(def ^:private sample-requester      "operator@miniforge.ai")

(defn- stream []
  (core/create-event-stream {:sinks []}))

(defn- now [] (java.util.Date.))

(defn- base-envelope
  "Minimal conforming envelope for hand-built schema tests.
   Does NOT include :workflow/id — schemas that make it optional
   accept the key's absence."
  [event-type]
  {:event/type            event-type
   :event/id              (random-uuid)
   :event/timestamp       (now)
   :event/version         event-version
   :event/sequence-number 0
   :message               (name event-type)})

;------------------------------------------------------------------------------ Layer 1
;; PrMonitorReviewCommentsArrived — schema validation + constructor round-trip

(defn- review-comments-arrived-map []
  (assoc (base-envelope :pr-monitor/review-comments-arrived)
         :pr/repo          sample-repo
         :pr/number        sample-pr-number
         :comments/count   sample-comments-count))

(deftest review-comments-arrived-valid-conforms-test
  (testing "conforming map passes PrMonitorReviewCommentsArrived schema"
    (is (m/validate schema/PrMonitorReviewCommentsArrived
                    (review-comments-arrived-map)))))

(deftest review-comments-arrived-missing-required-fields-fail-test
  (testing "missing :pr/repo fails"
    (is (false? (m/validate schema/PrMonitorReviewCommentsArrived
                            (dissoc (review-comments-arrived-map) :pr/repo)))))
  (testing "missing :pr/number fails"
    (is (false? (m/validate schema/PrMonitorReviewCommentsArrived
                            (dissoc (review-comments-arrived-map) :pr/number)))))
  (testing "missing :comments/count fails"
    (is (false? (m/validate schema/PrMonitorReviewCommentsArrived
                            (dissoc (review-comments-arrived-map) :comments/count)))))
  (testing "missing :message fails"
    (is (false? (m/validate schema/PrMonitorReviewCommentsArrived
                            (dissoc (review-comments-arrived-map) :message))))))

(deftest review-comments-arrived-optional-fields-test
  (testing ":comments/agent-session-id is optional — absent by default"
    (let [ev (review-comments-arrived-map)]
      (is (not (contains? ev :comments/agent-session-id)))
      (is (m/validate schema/PrMonitorReviewCommentsArrived ev))))
  (testing ":comments/agent-session-id accepted when present"
    (let [ev (assoc (review-comments-arrived-map)
                    :comments/agent-session-id (random-uuid))]
      (is (m/validate schema/PrMonitorReviewCommentsArrived ev))))
  (testing ":workflow/id accepted as nil"
    (let [ev (assoc (review-comments-arrived-map) :workflow/id nil)]
      (is (m/validate schema/PrMonitorReviewCommentsArrived ev)))))

(deftest review-comments-arrived-constructor-type-test
  (testing "constructor emits :pr-monitor/review-comments-arrived"
    (let [ev (core/pr-monitor-review-comments-arrived
              (stream) sample-repo sample-pr-number sample-comments-count)]
      (is (= :pr-monitor/review-comments-arrived (:event/type ev))))))

(deftest review-comments-arrived-constructor-envelope-fields-test
  (testing "constructor output carries uuid :event/id, inst :event/timestamp, typed envelope"
    (let [ev (core/pr-monitor-review-comments-arrived
              (stream) sample-repo sample-pr-number sample-comments-count)]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev))))))

(deftest review-comments-arrived-constructor-workflow-id-nil-test
  (testing "constructor emits nil :workflow/id (PR-scoped, not workflow-scoped)"
    (let [ev (core/pr-monitor-review-comments-arrived
              (stream) sample-repo sample-pr-number sample-comments-count)]
      (is (nil? (:workflow/id ev))))))

(deftest review-comments-arrived-constructor-domain-fields-test
  (testing "constructor populates all domain fields from args"
    (let [ev (core/pr-monitor-review-comments-arrived
              (stream) sample-repo sample-pr-number sample-comments-count)]
      (is (= sample-repo           (:pr/repo ev)))
      (is (= sample-pr-number      (:pr/number ev)))
      (is (= sample-comments-count (:comments/count ev))))))

(deftest review-comments-arrived-constructor-optional-session-id-test
  (testing "optional agent-session-id assoc'd when supplied"
    (let [session-id (random-uuid)
          ev         (core/pr-monitor-review-comments-arrived
                      (stream) sample-repo sample-pr-number sample-comments-count
                      session-id)]
      (is (= session-id (:comments/agent-session-id ev)))))
  (testing ":comments/agent-session-id absent when not supplied"
    (let [ev (core/pr-monitor-review-comments-arrived
              (stream) sample-repo sample-pr-number sample-comments-count)]
      (is (not (contains? ev :comments/agent-session-id))))))

(deftest review-comments-arrived-round-trip-test
  (testing "constructor output validates against PrMonitorReviewCommentsArrived schema"
    (let [ev (core/pr-monitor-review-comments-arrived
              (stream) sample-repo sample-pr-number sample-comments-count)]
      (is (m/validate schema/PrMonitorReviewCommentsArrived ev)
          (str "validation errors: "
               (pr-str (m/explain schema/PrMonitorReviewCommentsArrived ev)))))))

;------------------------------------------------------------------------------ Layer 2
;; PrMonitorCiFailed — schema validation + constructor round-trip

(defn- ci-failed-map []
  (assoc (base-envelope :pr-monitor/ci-failed)
         :pr/repo        sample-repo
         :pr/number      sample-pr-number
         :ci/check-name  sample-check-name
         :ci/conclusion  sample-conclusion))

(deftest ci-failed-valid-conforms-test
  (testing "conforming map passes PrMonitorCiFailed schema"
    (is (m/validate schema/PrMonitorCiFailed (ci-failed-map)))))

(deftest ci-failed-missing-required-fields-fail-test
  (testing "missing :pr/repo fails"
    (is (false? (m/validate schema/PrMonitorCiFailed
                            (dissoc (ci-failed-map) :pr/repo)))))
  (testing "missing :pr/number fails"
    (is (false? (m/validate schema/PrMonitorCiFailed
                            (dissoc (ci-failed-map) :pr/number)))))
  (testing "missing :ci/check-name fails"
    (is (false? (m/validate schema/PrMonitorCiFailed
                            (dissoc (ci-failed-map) :ci/check-name)))))
  (testing "missing :ci/conclusion fails"
    (is (false? (m/validate schema/PrMonitorCiFailed
                            (dissoc (ci-failed-map) :ci/conclusion)))))
  (testing "missing :message fails"
    (is (false? (m/validate schema/PrMonitorCiFailed
                            (dissoc (ci-failed-map) :message))))))

(deftest ci-failed-open-conclusion-keyword-test
  (testing ":ci/conclusion is an open keyword — additional values beyond :failure are accepted"
    (doseq [conclusion [:failure :timed-out :cancelled :startup-failure]]
      (let [ev (assoc (ci-failed-map) :ci/conclusion conclusion)]
        (is (m/validate schema/PrMonitorCiFailed ev)
            (str ":ci/conclusion " conclusion " should be accepted"))))))

(deftest ci-failed-constructor-type-test
  (testing "constructor emits :pr-monitor/ci-failed"
    (let [ev (core/pr-monitor-ci-failed
              (stream) sample-repo sample-pr-number sample-check-name sample-conclusion)]
      (is (= :pr-monitor/ci-failed (:event/type ev))))))

(deftest ci-failed-constructor-envelope-fields-test
  (testing "constructor output carries uuid :event/id, inst :event/timestamp"
    (let [ev (core/pr-monitor-ci-failed
              (stream) sample-repo sample-pr-number sample-check-name sample-conclusion)]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev))))))

(deftest ci-failed-constructor-workflow-id-nil-test
  (testing "constructor emits nil :workflow/id (PR-scoped)"
    (let [ev (core/pr-monitor-ci-failed
              (stream) sample-repo sample-pr-number sample-check-name sample-conclusion)]
      (is (nil? (:workflow/id ev))))))

(deftest ci-failed-constructor-domain-fields-test
  (testing "constructor populates all domain fields from args"
    (let [ev (core/pr-monitor-ci-failed
              (stream) sample-repo sample-pr-number sample-check-name sample-conclusion)]
      (is (= sample-repo       (:pr/repo ev)))
      (is (= sample-pr-number  (:pr/number ev)))
      (is (= sample-check-name (:ci/check-name ev)))
      (is (= sample-conclusion (:ci/conclusion ev))))))

(deftest ci-failed-round-trip-test
  (testing "constructor output validates against PrMonitorCiFailed schema"
    (let [ev (core/pr-monitor-ci-failed
              (stream) sample-repo sample-pr-number sample-check-name sample-conclusion)]
      (is (m/validate schema/PrMonitorCiFailed ev)
          (str "validation errors: "
               (pr-str (m/explain schema/PrMonitorCiFailed ev)))))))

;------------------------------------------------------------------------------ Layer 3
;; StandardsReviewPosted — schema validation + constructor round-trip

(defn- standards-review-map []
  (assoc (base-envelope :standards-review/posted)
         :pr/repo          sample-repo
         :pr/number        sample-pr-number
         :review/severity  sample-severity))

(deftest standards-review-posted-valid-conforms-test
  (testing "conforming map passes StandardsReviewPosted schema"
    (is (m/validate schema/StandardsReviewPosted (standards-review-map)))))

(deftest standards-review-posted-missing-required-fields-fail-test
  (testing "missing :pr/repo fails"
    (is (false? (m/validate schema/StandardsReviewPosted
                            (dissoc (standards-review-map) :pr/repo)))))
  (testing "missing :pr/number fails"
    (is (false? (m/validate schema/StandardsReviewPosted
                            (dissoc (standards-review-map) :pr/number)))))
  (testing "missing :review/severity fails"
    (is (false? (m/validate schema/StandardsReviewPosted
                            (dissoc (standards-review-map) :review/severity)))))
  (testing "missing :message fails"
    (is (false? (m/validate schema/StandardsReviewPosted
                            (dissoc (standards-review-map) :message))))))

(deftest standards-review-posted-optional-fields-test
  (testing ":affected/workflow-run-id is optional — absent by default"
    (let [ev (standards-review-map)]
      (is (not (contains? ev :affected/workflow-run-id)))
      (is (m/validate schema/StandardsReviewPosted ev))))
  (testing ":affected/workflow-run-id accepted when present"
    (let [ev (assoc (standards-review-map)
                    :affected/workflow-run-id (random-uuid))]
      (is (m/validate schema/StandardsReviewPosted ev))))
  (testing ":workflow/id nil accepted"
    (let [ev (assoc (standards-review-map) :workflow/id nil)]
      (is (m/validate schema/StandardsReviewPosted ev)))))

(deftest standards-review-posted-open-severity-keyword-test
  (testing ":review/severity is an open keyword — :advisory and :blocking accepted"
    (doseq [sev [sample-severity blocking-severity :info :error]]
      (let [ev (assoc (standards-review-map) :review/severity sev)]
        (is (m/validate schema/StandardsReviewPosted ev)
            (str ":review/severity " sev " should be accepted"))))))

(deftest standards-review-posted-constructor-type-test
  (testing "constructor emits :standards-review/posted"
    (let [ev (core/standards-review-posted
              (stream) sample-repo sample-pr-number sample-severity)]
      (is (= :standards-review/posted (:event/type ev))))))

(deftest standards-review-posted-constructor-envelope-fields-test
  (testing "constructor output carries uuid :event/id, inst :event/timestamp"
    (let [ev (core/standards-review-posted
              (stream) sample-repo sample-pr-number sample-severity)]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev))))))

(deftest standards-review-posted-constructor-workflow-id-nil-test
  (testing "constructor emits nil :workflow/id (PR-scoped)"
    (let [ev (core/standards-review-posted
              (stream) sample-repo sample-pr-number sample-severity)]
      (is (nil? (:workflow/id ev))))))

(deftest standards-review-posted-constructor-domain-fields-test
  (testing "constructor populates all domain fields from args"
    (let [ev (core/standards-review-posted
              (stream) sample-repo sample-pr-number sample-severity)]
      (is (= sample-repo     (:pr/repo ev)))
      (is (= sample-pr-number (:pr/number ev)))
      (is (= sample-severity (:review/severity ev))))))

(deftest standards-review-posted-constructor-optional-affected-workflow-test
  (testing "optional affected-workflow-run-id assoc'd when supplied"
    (let [wf-id (random-uuid)
          ev    (core/standards-review-posted
                 (stream) sample-repo sample-pr-number sample-severity wf-id)]
      (is (= wf-id (:affected/workflow-run-id ev)))))
  (testing ":affected/workflow-run-id absent when not supplied"
    (let [ev (core/standards-review-posted
              (stream) sample-repo sample-pr-number sample-severity)]
      (is (not (contains? ev :affected/workflow-run-id))))))

(deftest standards-review-posted-round-trip-test
  (testing "constructor output validates against StandardsReviewPosted schema"
    (let [ev (core/standards-review-posted
              (stream) sample-repo sample-pr-number sample-severity)]
      (is (m/validate schema/StandardsReviewPosted ev)
          (str "validation errors: "
               (pr-str (m/explain schema/StandardsReviewPosted ev)))))))

;------------------------------------------------------------------------------ Layer 4
;; AutomationEdgeUpserted — schema validation only (no public constructor)

(defn- automation-edge-map []
  (assoc (base-envelope :supervisory/automation-edge-upserted)
         :supervisory/entity {:edge/id    (random-uuid)
                              :edge/state :observed}))

(deftest automation-edge-upserted-valid-conforms-test
  (testing "conforming map passes AutomationEdgeUpserted schema"
    (is (m/validate schema/AutomationEdgeUpserted (automation-edge-map)))))

(deftest automation-edge-upserted-missing-required-fields-fail-test
  (testing "missing :supervisory/entity fails"
    (is (false? (m/validate schema/AutomationEdgeUpserted
                            (dissoc (automation-edge-map) :supervisory/entity)))))
  (testing "missing :message fails"
    (is (false? (m/validate schema/AutomationEdgeUpserted
                            (dissoc (automation-edge-map) :message))))))

(deftest automation-edge-upserted-entity-not-a-map-fails-test
  (testing "non-map :supervisory/entity fails (entity must be map?)"
    (is (false? (m/validate schema/AutomationEdgeUpserted
                            (assoc (automation-edge-map) :supervisory/entity "not-a-map")))))
  (testing "vector :supervisory/entity fails"
    (is (false? (m/validate schema/AutomationEdgeUpserted
                            (assoc (automation-edge-map) :supervisory/entity [:edge]))))))

(deftest automation-edge-upserted-workflow-id-variants-test
  (testing ":workflow/id nil accepted (pre-correlation edge, no handler workflow yet)"
    (let [ev (assoc (automation-edge-map) :workflow/id nil)]
      (is (m/validate schema/AutomationEdgeUpserted ev))))
  (testing ":workflow/id absent accepted"
    (let [ev (dissoc (automation-edge-map) :workflow/id)]
      (is (m/validate schema/AutomationEdgeUpserted ev))))
  (testing ":workflow/id uuid accepted (post-correlation edge)"
    (let [ev (assoc (automation-edge-map) :workflow/id (random-uuid))]
      (is (m/validate schema/AutomationEdgeUpserted ev)))))

(deftest automation-edge-upserted-open-entity-map-test
  (testing "entity map may carry any fields — schema does not constrain its shape"
    (let [ev (assoc (automation-edge-map)
                    :supervisory/entity
                    {:edge/id              (random-uuid)
                     :edge/state           :handled
                     :edge/trigger-kind    :pr-merged
                     :edge/handled-by-wfid (random-uuid)
                     :extra-future-field   "tolerated"})]
      (is (m/validate schema/AutomationEdgeUpserted ev)))))

;------------------------------------------------------------------------------ Layer 5
;; InterventionRequested — schema validation + constructor round-trip

(defn- intervention-data []
  (let [t (now)]
    {:intervention/id             (random-uuid)
     :intervention/type           pause-type
     :intervention/target-type    target-type
     :intervention/target-id      (random-uuid)
     :intervention/requested-by   sample-requester
     :intervention/request-source request-source
     :intervention/state          proposed-state
     :intervention/requested-at   t
     :intervention/updated-at     t}))

(defn- intervention-requested-map []
  (merge (base-envelope :supervisory/intervention-requested)
         (intervention-data)))

(deftest intervention-requested-valid-conforms-test
  (testing "conforming map passes InterventionRequested schema"
    (is (m/validate schema/InterventionRequested (intervention-requested-map)))))

(deftest intervention-requested-missing-required-fields-fail-test
  (testing "missing :intervention/id fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/id)))))
  (testing "missing :intervention/type fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/type)))))
  (testing "missing :intervention/target-type fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/target-type)))))
  (testing "missing :intervention/target-id fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/target-id)))))
  (testing "missing :intervention/requested-by fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/requested-by)))))
  (testing "missing :intervention/request-source fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/request-source)))))
  (testing "missing :intervention/state fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/state)))))
  (testing "missing :intervention/requested-at fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/requested-at)))))
  (testing "missing :intervention/updated-at fails"
    (is (false? (m/validate schema/InterventionRequested
                            (dissoc (intervention-requested-map) :intervention/updated-at))))))

(deftest intervention-requested-optional-fields-test
  (testing ":intervention/justification, :details, :approval-required? may be absent"
    (let [ev (intervention-requested-map)]
      (is (not (contains? ev :intervention/justification)))
      (is (not (contains? ev :intervention/details)))
      (is (not (contains? ev :intervention/approval-required?)))
      (is (m/validate schema/InterventionRequested ev))))
  (testing "optional fields accepted when present"
    (let [ev (assoc (intervention-requested-map)
                    :intervention/justification "operator initiated pause"
                    :intervention/details       {:reason :manual}
                    :intervention/approval-required? false)]
      (is (m/validate schema/InterventionRequested ev)))))

(deftest intervention-requested-constructor-type-test
  (testing "constructor emits :supervisory/intervention-requested"
    (let [ev (core/intervention-requested (stream) (random-uuid) (intervention-data))]
      (is (= :supervisory/intervention-requested (:event/type ev))))))

(deftest intervention-requested-constructor-envelope-fields-test
  (testing "constructor output carries uuid :event/id, inst :event/timestamp, workflow-id"
    (let [wf-id (random-uuid)
          ev    (core/intervention-requested (stream) wf-id (intervention-data))]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev)))
      (is (= wf-id (:workflow/id ev))))))

(deftest intervention-requested-constructor-domain-fields-test
  (testing "constructor merges all intervention fields onto the envelope"
    (let [data (intervention-data)
          ev   (core/intervention-requested (stream) (random-uuid) data)]
      (is (= (:intervention/id data)     (:intervention/id ev)))
      (is (= pause-type                  (:intervention/type ev)))
      (is (= target-type                 (:intervention/target-type ev)))
      (is (= sample-requester            (:intervention/requested-by ev)))
      (is (= request-source              (:intervention/request-source ev)))
      (is (= proposed-state              (:intervention/state ev))))))

(deftest intervention-requested-round-trip-test
  (testing "constructor output validates against InterventionRequested schema"
    (let [ev (core/intervention-requested (stream) (random-uuid) (intervention-data))]
      (is (m/validate schema/InterventionRequested ev)
          (str "validation errors: "
               (pr-str (m/explain schema/InterventionRequested ev)))))))

;------------------------------------------------------------------------------ Layer 6
;; InterventionStateChanged — schema validation + constructor round-trip

(defn- intervention-state-changed-map []
  (assoc (base-envelope :supervisory/intervention-state-changed)
         :intervention/id    (random-uuid)
         :intervention/state applied-state))

(deftest intervention-state-changed-valid-conforms-test
  (testing "conforming map passes InterventionStateChanged schema"
    (is (m/validate schema/InterventionStateChanged (intervention-state-changed-map)))))

(deftest intervention-state-changed-missing-required-fields-fail-test
  (testing "missing :intervention/id fails"
    (is (false? (m/validate schema/InterventionStateChanged
                            (dissoc (intervention-state-changed-map) :intervention/id)))))
  (testing "missing :intervention/state fails"
    (is (false? (m/validate schema/InterventionStateChanged
                            (dissoc (intervention-state-changed-map) :intervention/state)))))
  (testing "missing :message fails"
    (is (false? (m/validate schema/InterventionStateChanged
                            (dissoc (intervention-state-changed-map) :message))))))

(deftest intervention-state-changed-optional-fields-test
  (testing "all transition-detail fields are optional"
    (let [ev (intervention-state-changed-map)]
      (is (not (contains? ev :intervention/from-state)))
      (is (not (contains? ev :intervention/type)))
      (is (not (contains? ev :intervention/outcome)))
      (is (not (contains? ev :intervention/reason)))
      (is (not (contains? ev :intervention/requested-at)))
      (is (m/validate schema/InterventionStateChanged ev))))
  (testing "optional transition fields accepted when present"
    (let [ev (assoc (intervention-state-changed-map)
                    :intervention/from-state     dispatched-state
                    :intervention/type           pause-type
                    :intervention/outcome        {:paused true}
                    :intervention/reason         "operator approved"
                    :intervention/requested-at   (now)
                    :intervention/updated-at     (now))]
      (is (m/validate schema/InterventionStateChanged ev)))))

(deftest intervention-state-changed-constructor-type-test
  (testing "constructor emits :supervisory/intervention-state-changed"
    (let [iid (random-uuid)
          ev  (core/intervention-state-changed
               (stream) (random-uuid) iid applied-state)]
      (is (= :supervisory/intervention-state-changed (:event/type ev))))))

(deftest intervention-state-changed-constructor-envelope-fields-test
  (testing "constructor output carries uuid :event/id, inst :event/timestamp, workflow-id"
    (let [wf-id (random-uuid)
          iid   (random-uuid)
          ev    (core/intervention-state-changed (stream) wf-id iid applied-state)]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev)))
      (is (= wf-id (:workflow/id ev))))))

(deftest intervention-state-changed-constructor-domain-fields-test
  (testing "constructor stamps :intervention/id and :intervention/state from args"
    (let [iid (random-uuid)
          ev  (core/intervention-state-changed
               (stream) (random-uuid) iid applied-state)]
      (is (= iid          (:intervention/id ev)))
      (is (= applied-state (:intervention/state ev))))))

(deftest intervention-state-changed-constructor-optional-from-state-test
  (testing ":intervention/from-state assoc'd when supplied via opts"
    (let [iid (random-uuid)
          ev  (core/intervention-state-changed
               (stream) (random-uuid) iid applied-state
               {:intervention/from-state dispatched-state})]
      (is (= dispatched-state (:intervention/from-state ev)))))
  (testing ":intervention/from-state absent when opts are empty"
    (let [ev (core/intervention-state-changed
              (stream) (random-uuid) (random-uuid) applied-state)]
      (is (not (contains? ev :intervention/from-state))))))

(deftest intervention-state-changed-constructor-optional-outcome-test
  (testing ":intervention/outcome rides through from opts"
    (let [outcome {:paused true :reason "approved"}
          ev      (core/intervention-state-changed
                   (stream) (random-uuid) (random-uuid) applied-state
                   {:intervention/outcome outcome})]
      (is (= outcome (:intervention/outcome ev))))))

(deftest intervention-state-changed-round-trip-test
  (testing "constructor output validates against InterventionStateChanged schema"
    (let [ev (core/intervention-state-changed
              (stream) (random-uuid) (random-uuid) applied-state)]
      (is (m/validate schema/InterventionStateChanged ev)
          (str "validation errors: "
               (pr-str (m/explain schema/InterventionStateChanged ev)))))))

(deftest intervention-state-changed-round-trip-with-opts-test
  (testing "constructor output with opts validates against InterventionStateChanged schema"
    (let [ev (core/intervention-state-changed
              (stream) (random-uuid) (random-uuid) applied-state
              {:intervention/from-state     dispatched-state
               :intervention/type           pause-type
               :intervention/requested-by   sample-requester
               :intervention/requested-at   (now)
               :intervention/updated-at     (now)
               :intervention/approval-required? false})]
      (is (m/validate schema/InterventionStateChanged ev)
          (str "validation errors: "
               (pr-str (m/explain schema/InterventionStateChanged ev)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Smoke-run all 6 schema+constructor pairs from the REPL.
  ;; Routing triggers (workflow/id nil):
  (m/validate schema/PrMonitorReviewCommentsArrived
              (core/pr-monitor-review-comments-arrived
               (core/create-event-stream {:sinks []})
               "owner/repo" 1 2))
  (m/validate schema/PrMonitorCiFailed
              (core/pr-monitor-ci-failed
               (core/create-event-stream {:sinks []})
               "owner/repo" 1 "build" :failure))
  (m/validate schema/StandardsReviewPosted
              (core/standards-review-posted
               (core/create-event-stream {:sinks []})
               "owner/repo" 1 :advisory))
  ;; AutomationEdgeUpserted — hand-built (no constructor):
  (m/validate schema/AutomationEdgeUpserted
              {:event/type :supervisory/automation-edge-upserted
               :event/id (random-uuid) :event/timestamp (java.util.Date.)
               :event/version "1.0.0" :event/sequence-number 0
               :supervisory/entity {:edge/id (random-uuid)} :message "ok"})
  :leave-this-here)
