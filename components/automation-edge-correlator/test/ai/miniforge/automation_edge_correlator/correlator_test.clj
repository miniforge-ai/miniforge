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

(ns ai.miniforge.automation-edge-correlator.correlator-test
  "Unit tests for the pure state-machine layer.

   Covers every transition in N5-delta-4 §2.4 plus the §2.3 idempotency
   invariant. Fixtures are obviously synthetic — random UUIDs and synthetic
   PR numbers (999) per the workspace fabrication-evidence rule."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.automation-edge-correlator.correlator :as sut]
   [ai.miniforge.automation-edge-correlator.schema :as schema])
  (:import
   (java.util Date)))

;------------------------------------------------------------------------------ Layer 0
;; Constants + factories

(def ^:private suppression-window-ms
  "Five-minute suppression window — matches the §6 default."
  300000)

(def ^:private synthetic-pr
  "Synthetic [repo number] pair used everywhere a trigger payload needs PR
   identity. Number 999 keeps fixtures obviously fake."
  ["miniforge-ai/miniforge" 999])

(defn- at-ms
  "Build a `java.util.Date` at the given epoch-millis."
  ^Date [^long ms]
  (Date. ms))

(defn- classified-trigger
  "Factory for a classified-trigger map. Override individual keys via
   trailing kwargs; defaults synthesize a `:pr-merged` trigger with one PR."
  [trigger-id & {:as overrides}]
  (merge {:trigger/event-id                  trigger-id
          :trigger/kind                      :pr-merged
          :trigger/affected-pr-ids           [synthetic-pr]
          :trigger/affected-agent-session-ids []}
         overrides))

(defn- workflow-completion
  "Factory for the payload `apply-workflow-completed` / `apply-workflow-failed`
   consumes."
  [trigger-id workflow-id timestamp]
  {:routing/trigger-event-id trigger-id
   :workflow/id              workflow-id
   :workflow/timestamp       timestamp})

(defn- observe
  "Seed `empty-state` with one `:observed` edge for `trigger-id`. Returns
   `[state edge]`."
  ([trigger-id] (observe trigger-id (at-ms 1000)))
  ([trigger-id now]
   (sut/apply-trigger sut/empty-state (classified-trigger trigger-id) now)))

;------------------------------------------------------------------------------ Layer 1
;; edge-id-for — §2.3 idempotency invariant

(deftest edge-id-for-is-deterministic
  (testing "two derivations from the same trigger-event-id produce =-equal ids"
    (let [tid (random-uuid)]
      (is (= (sut/edge-id-for tid) (sut/edge-id-for tid))))))

(deftest edge-id-for-distinct-inputs-produce-distinct-ids
  (testing "two distinct trigger-event-ids derive to distinct edge ids"
    (let [a (random-uuid)
          b (random-uuid)]
      (is (not= (sut/edge-id-for a) (sut/edge-id-for b))))))

(deftest edge-id-for-accepts-string-input
  (testing "string input coerces through UUID/fromString and matches UUID input"
    (let [tid (random-uuid)]
      (is (= (sut/edge-id-for tid) (sut/edge-id-for (str tid)))))))

(deftest edge-id-for-uses-component-namespace
  (testing "derivation differs from the nil-UUID namespace (catches accidental nil-ns regression)"
    (let [tid       (random-uuid)
          nil-ns-id (#'sut/uuid-v5 (java.util.UUID. 0 0) (str tid))]
      (is (not= nil-ns-id (sut/edge-id-for tid))
          "edge-id-for must not derive against the nil UUID namespace"))))

;------------------------------------------------------------------------------ Layer 1
;; apply-trigger — :observed open + idempotency

(deftest apply-trigger-opens-observed-edge
  (testing "fresh trigger inserts an :observed edge into :pending"
    (let [tid       (random-uuid)
          now       (at-ms 1000)
          [state e] (sut/apply-trigger sut/empty-state
                                       (classified-trigger tid)
                                       now)]
      (is (= :observed (:edge/status e)))
      (is (= tid (:edge/trigger-event-id e)))
      (is (= (sut/edge-id-for tid) (:edge/id e)))
      (is (= (str tid) (:edge/idempotency-key e)))
      (is (= now (:edge/occurred-at e)))
      (is (= now (:edge/updated-at e)))
      (is (false? (:edge/operator-action-required e)))
      (is (= e (get-in state [:pending tid]))
          "edge is keyed in :pending by :edge/trigger-event-id"))))

(deftest apply-trigger-is-idempotent
  (testing "re-observing the same trigger-event-id returns the stored edge and unchanged state"
    (let [tid          (random-uuid)
          [s1 e1]      (observe tid (at-ms 1000))
          [s2 e2]      (sut/apply-trigger s1
                                          (classified-trigger tid)
                                          (at-ms 9999))]
      (is (= e1 e2)
          "second observation returns byte-identical edge (per §2.3 / §3.6)")
      (is (= s1 s2)
          "state is unchanged on re-observation"))))

(deftest apply-trigger-stamps-affected-fields
  (testing "affected PRs and agent sessions are carried onto the edge"
    (let [tid       (random-uuid)
          agent-id  (random-uuid)
          [_ e]     (sut/apply-trigger sut/empty-state
                                       (classified-trigger
                                         tid
                                         :trigger/affected-agent-session-ids [agent-id])
                                       (at-ms 1000))]
      (is (= [synthetic-pr] (:edge/affected-pr-ids e)))
      (is (= [agent-id] (:edge/affected-agent-session-ids e))))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :handled

(deftest observed-to-handled-on-workflow-completed
  (testing "matching :workflow/completed transitions to :handled and evicts"
    (let [tid         (random-uuid)
          wf-id       (random-uuid)
          [s1 _]      (observe tid (at-ms 1000))
          completion  (workflow-completion tid wf-id (at-ms 2000))
          [s2 edge]   (sut/apply-workflow-completed s1 completion)]
      (is (= :handled (:edge/status edge)))
      (is (= wf-id (:edge/handled-by-workflow-run-id edge)))
      (is (false? (:edge/operator-action-required edge)))
      (is (= (at-ms 2000) (:edge/updated-at edge)))
      (is (nil? (get-in s2 [:pending tid]))
          "terminal status evicts from :pending per §3.6 bullet 1"))))

(deftest workflow-completed-without-match-is-noop
  (testing "completion with no pending edge leaves state unchanged and returns nil"
    (let [orphan-tid (random-uuid)
          completion (workflow-completion orphan-tid (random-uuid) (at-ms 2000))
          [s2 edge]  (sut/apply-workflow-completed sut/empty-state completion)]
      (is (nil? edge))
      (is (= sut/empty-state s2)
          "correlator does not invent correlations (§3.5)"))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :failed

(deftest observed-to-failed-on-workflow-failed
  (testing "matching :workflow/failed transitions to :failed, sets operator-action, evicts"
    (let [tid       (random-uuid)
          wf-id     (random-uuid)
          [s1 _]    (observe tid (at-ms 1000))
          failure   (workflow-completion tid wf-id (at-ms 2000))
          [s2 edge] (sut/apply-workflow-failed s1 failure)]
      (is (= :failed (:edge/status edge)))
      (is (= wf-id (:edge/handled-by-workflow-run-id edge)))
      (is (true? (:edge/operator-action-required edge)))
      (is (nil? (get-in s2 [:pending tid]))))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :needs-operator (timeout arm)

(deftest expire-pending-transitions-aged-edges
  (testing "edges older than suppression-window transition to :needs-operator and evict"
    (let [tid        (random-uuid)
          [s1 _]     (observe tid (at-ms 1000))
          ;; 1000 + 300000 + 1 = past the window
          now        (at-ms 301001)
          [s2 edges] (sut/expire-pending s1 now suppression-window-ms)
          edge       (first edges)]
      (is (= 1 (count edges)))
      (is (= :needs-operator (:edge/status edge)))
      (is (true? (:edge/operator-action-required edge)))
      (is (= :edge/manual-disposition-required
             (:edge/fallback-intervention edge)))
      (is (= now (:edge/updated-at edge)))
      (is (nil? (get-in s2 [:pending tid]))))))

(deftest expire-pending-leaves-fresh-edges
  (testing "edges within the suppression-window are not transitioned"
    (let [tid    (random-uuid)
          [s1 _] (observe tid (at-ms 1000))
          ;; Well within the 5-minute window
          now    (at-ms 60000)
          [s2 edges] (sut/expire-pending s1 now suppression-window-ms)]
      (is (empty? edges))
      (is (= s1 s2)))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :needs-operator (no-handler arm)

(deftest observed-to-needs-operator-on-no-handler
  (testing "apply-no-handler transitions a pending edge to :needs-operator and evicts"
    (let [tid       (random-uuid)
          [s1 _]    (observe tid (at-ms 1000))
          signal    {:routing/trigger-event-id tid :timestamp (at-ms 2000)}
          [s2 edge] (sut/apply-no-handler s1 signal)]
      (is (= :needs-operator (:edge/status edge)))
      (is (true? (:edge/operator-action-required edge)))
      (is (= :edge/manual-disposition-required
             (:edge/fallback-intervention edge)))
      (is (nil? (get-in s2 [:pending tid]))))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :suppressed

(deftest observed-to-suppressed-on-intervention
  (testing "apply-suppress transitions a pending edge to :suppressed and evicts"
    (let [tid       (random-uuid)
          [s1 _]    (observe tid (at-ms 1000))
          interv    {:edge/trigger-event-id tid :timestamp (at-ms 2000)}
          [s2 edge] (sut/apply-suppress s1 interv)]
      (is (= :suppressed (:edge/status edge)))
      (is (false? (:edge/operator-action-required edge)))
      (is (= (at-ms 2000) (:edge/updated-at edge)))
      (is (nil? (get-in s2 [:pending tid]))))))

;; NOTE — §2.4 contemplates `:handled → :suppressed` (operator suppresses a
;; terminal edge). The in-memory state machine only tracks `:observed` edges
;; (terminals evict on transition per §3.6 bullet 1), so this transition is
;; out of scope for the pure layer. It is handled by the operator
;; intervention surface (N15-6+) operating directly on the consumer entity
;; table.

;------------------------------------------------------------------------------ Layer 1
;; Idempotency-key derivation

(deftest idempotency-key-is-string-of-trigger-event-id
  (testing "stored :edge/idempotency-key equals (str trigger-event-id)"
    (let [tid   (random-uuid)
          [_ e] (observe tid)]
      (is (= (str tid) (:edge/idempotency-key e))))))

(deftest namespace-uuid-is-the-component-constant
  (testing "the namespace constant is the schema-level def, not the nil UUID"
    (is (not= (java.util.UUID. 0 0) schema/automation-edge-namespace))))
