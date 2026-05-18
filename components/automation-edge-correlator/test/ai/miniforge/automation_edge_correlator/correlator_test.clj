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

   Covers every transition in N5-delta-4 §2.4, the §2.3 idempotency
   invariant (including the post-terminal-eviction replay case), the
   handler-started protection on the timeout arm, and the post-terminal
   `:edge/suppress` case.

   Fixtures use the canonical envelope key `:event/timestamp` (matching
   `event_stream/schema.clj`), not synthetic `:workflow/timestamp` or
   `:timestamp` shapes — Copilot caught that earlier fixtures exercised a
   payload the event stream never emits."
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
  "Factory for a classified-trigger map. `:trigger/occurred-at` defaults to
   `(at-ms 1000)` (epoch+1s — obviously synthetic, deterministic). Override
   any field via trailing kwargs."
  [trigger-id & {:as overrides}]
  (merge {:trigger/event-id                   trigger-id
          :trigger/kind                       :pr-merged
          :trigger/occurred-at                (at-ms 1000)
          :trigger/affected-pr-ids            [synthetic-pr]
          :trigger/affected-agent-session-ids []}
         overrides))

(defn- workflow-started
  "Factory for the payload `apply-workflow-started` consumes. The routing
   id is the bridge between `apply-trigger` and the later
   completion/failure arms — it lives on `:workflow/started` per
   N5-delta-4 §4.3, NOT on completion / failure events."
  ([workflow-id trigger-id] (workflow-started workflow-id trigger-id (at-ms 1100)))
  ([workflow-id trigger-id event-timestamp]
   {:workflow/id              workflow-id
    :routing/trigger-event-id trigger-id
    :event/timestamp          event-timestamp}))

(defn- workflow-terminal
  "Factory for `:workflow/completed` / `:workflow/failed` payloads. Carries
   only `:workflow/id` + `:event/timestamp` — the trigger-event-id is
   looked up via the correlator's `:workflow-index`, NOT carried on the
   event itself."
  [workflow-id event-timestamp]
  {:workflow/id     workflow-id
   :event/timestamp event-timestamp})

(defn- observe
  "Seed `empty-state` with one `:observed` edge for `trigger-id`. Returns
   `[state edge]`."
  ([trigger-id]
   (observe trigger-id (at-ms 1000)))
  ([trigger-id occurred-at]
   (sut/apply-trigger sut/empty-state
                      (classified-trigger trigger-id
                                          :trigger/occurred-at occurred-at))))

(defn- observe+start
  "Open a trigger, then index its handler workflow as started. Returns
   `[state trigger-edge workflow-id]`."
  [trigger-id]
  (let [wf-id   (random-uuid)
        [s1 e]  (observe trigger-id)
        [s2 _]  (sut/apply-workflow-started s1 (workflow-started wf-id trigger-id))]
    [s2 e wf-id]))

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
;; apply-trigger — :observed open + idempotency (in-pending AND post-terminal)

(deftest apply-trigger-opens-observed-edge
  (testing "fresh trigger inserts an :observed edge into :pending"
    (let [tid        (random-uuid)
          occurred   (at-ms 1000)
          [state e]  (sut/apply-trigger sut/empty-state
                                        (classified-trigger
                                          tid
                                          :trigger/occurred-at occurred))]
      (is (= :observed (:edge/status e)))
      (is (= tid (:edge/trigger-event-id e)))
      (is (= (sut/edge-id-for tid) (:edge/id e)))
      (is (= (str tid) (:edge/idempotency-key e)))
      (is (= occurred (:edge/occurred-at e))
          ":edge/occurred-at carries the trigger event's own timestamp, not wall-clock")
      (is (= occurred (:edge/updated-at e)))
      (is (false? (:edge/operator-action-required e)))
      (is (= e (get-in state [:pending tid]))
          "edge is keyed in :pending by :edge/trigger-event-id"))))

(deftest apply-trigger-is-idempotent-while-pending
  (testing "re-observing the same trigger-event-id returns the stored edge and unchanged state"
    (let [tid     (random-uuid)
          [s1 e1] (observe tid (at-ms 1000))
          ;; Second observation uses a later timestamp — but state is unchanged
          ;; and the returned edge is byte-identical with the original.
          [s2 e2] (sut/apply-trigger
                    s1
                    (classified-trigger tid :trigger/occurred-at (at-ms 9999)))]
      (is (= e1 e2)
          "second observation returns byte-identical edge (per §2.3 / §3.6)")
      (is (= s1 s2)
          "state is unchanged on re-observation"))))

(deftest apply-trigger-is-idempotent-after-terminal-eviction
  (testing "re-observing AFTER terminal eviction returns the terminal edge (no regression)"
    (let [tid        (random-uuid)
          [s1 _ wf]  (observe+start tid)
          ;; Drive the edge terminal via :handled.
          [s2 _]     (sut/apply-workflow-completed
                       s1 (workflow-terminal wf (at-ms 2000)))
          terminal   (get-in s2 [:terminal tid])
          _          (is (= :handled (:edge/status terminal)))
          ;; Now replay or duplicate the original trigger event.
          [s3 e]     (sut/apply-trigger
                       s2 (classified-trigger tid :trigger/occurred-at (at-ms 1000)))]
      (is (= terminal e)
          (str "post-terminal re-observation MUST return the terminal edge, "
               "not a fresh :observed — otherwise the consumer's projection "
               "regresses :handled → :observed on duplicate delivery"))
      (is (= s2 s3)
          "state is unchanged on post-terminal re-observation"))))

(deftest apply-trigger-stamps-affected-fields
  (testing "affected PRs and agent sessions are carried onto the edge"
    (let [tid       (random-uuid)
          agent-id  (random-uuid)
          [_ e]     (sut/apply-trigger
                      sut/empty-state
                      (classified-trigger
                        tid
                        :trigger/affected-agent-session-ids [agent-id]))]
      (is (= [synthetic-pr] (:edge/affected-pr-ids e)))
      (is (= [agent-id] (:edge/affected-agent-session-ids e))))))

;------------------------------------------------------------------------------ Layer 1
;; apply-workflow-started — workflow-index population

(deftest apply-workflow-started-indexes-with-routing-id
  (testing "a :workflow/started carrying :routing/trigger-event-id indexes the workflow"
    (let [tid    (random-uuid)
          wf-id  (random-uuid)
          [s _]  (sut/apply-workflow-started
                   sut/empty-state (workflow-started wf-id tid))]
      (is (= tid (get-in s [:workflow-index wf-id]))))))

(deftest apply-workflow-started-without-routing-id-is-noop
  (testing "operator-initiated workflows (no :routing/trigger-event-id) are NOT indexed"
    (let [wf-id (random-uuid)
          [s _] (sut/apply-workflow-started
                  sut/empty-state
                  {:workflow/id wf-id :event/timestamp (at-ms 1000)})]
      (is (= sut/empty-state s)))))

(deftest apply-workflow-started-tolerates-out-of-order
  (testing "started lands before its trigger: index recorded; later trigger opens normally"
    (let [tid    (random-uuid)
          wf-id  (random-uuid)
          [s1 _] (sut/apply-workflow-started
                   sut/empty-state (workflow-started wf-id tid))
          [s2 e] (sut/apply-trigger s1 (classified-trigger tid))]
      (is (= tid (get-in s2 [:workflow-index wf-id])))
      (is (= :observed (:edge/status e))))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :handled

(deftest observed-to-handled-via-workflow-index
  (testing "matching :workflow/completed transitions via workflow-index, evicts, terminalizes"
    (let [tid          (random-uuid)
          [s1 _ wf]    (observe+start tid)
          completion   (workflow-terminal wf (at-ms 2000))
          [s2 edge]    (sut/apply-workflow-completed s1 completion)]
      (is (= :handled (:edge/status edge)))
      (is (= wf (:edge/handled-by-workflow-run-id edge)))
      (is (false? (:edge/operator-action-required edge)))
      (is (= (at-ms 2000) (:edge/updated-at edge))
          ":edge/updated-at carries the :event/timestamp from the completion event")
      (is (nil? (get-in s2 [:pending tid]))
          "terminal status evicts from :pending per §3.6 bullet 1")
      (is (nil? (get-in s2 [:workflow-index wf]))
          ":workflow-index is evicted with the edge")
      (is (= edge (get-in s2 [:terminal tid]))
          "terminal edge is retained for replay + post-terminal suppress"))))

(deftest workflow-completed-without-index-entry-is-noop
  (testing "completion for an unindexed workflow leaves state unchanged"
    (let [orphan-wf  (random-uuid)
          completion (workflow-terminal orphan-wf (at-ms 2000))
          [s2 edge]  (sut/apply-workflow-completed sut/empty-state completion)]
      (is (nil? edge))
      (is (= sut/empty-state s2)
          "correlator does not invent correlations (§3.5)"))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :failed

(deftest observed-to-failed-via-workflow-index
  (testing "matching :workflow/failed transitions to :failed, sets operator-action, evicts"
    (let [tid       (random-uuid)
          [s1 _ wf] (observe+start tid)
          failure   (workflow-terminal wf (at-ms 2000))
          [s2 edge] (sut/apply-workflow-failed s1 failure)]
      (is (= :failed (:edge/status edge)))
      (is (= wf (:edge/handled-by-workflow-run-id edge)))
      (is (true? (:edge/operator-action-required edge)))
      (is (= (at-ms 2000) (:edge/updated-at edge)))
      (is (nil? (get-in s2 [:pending tid])))
      (is (nil? (get-in s2 [:workflow-index wf])))
      (is (= :failed (:edge/status (get-in s2 [:terminal tid])))))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :needs-operator — timeout arm + handler-started protection

(deftest expire-pending-transitions-aged-edges-with-no-handler
  (testing "edges older than the window AND with no handler observed transition"
    (let [tid        (random-uuid)
          [s1 _]     (observe tid (at-ms 1000))
          ;; 1000 + 300000 + 1 = past the window
          now        (at-ms 301001)
          [s2 edges] (sut/expire-pending s1 now suppression-window-ms)
          edge       (first edges)]
      (is (= 1 (count edges)))
      (is (= :needs-operator (:edge/status edge)))
      (is (true? (:edge/operator-action-required edge)))
      (is (= now (:edge/updated-at edge)))
      (is (nil? (get-in s2 [:pending tid])))
      (is (= :needs-operator (:edge/status (get-in s2 [:terminal tid])))
          "expired edge moves to :terminal to keep duplicate observations dedup'd")
      (is (not (contains? edge :edge/fallback-intervention))
          (str "v1 omits :edge/fallback-intervention until N15-6+ defines the "
               "per-trigger-kind vocabulary (placeholder keyword would not be "
               "in the bounded intervention set)")))))

(deftest expire-pending-leaves-fresh-edges
  (testing "edges within the suppression-window are not transitioned"
    (let [tid        (random-uuid)
          [s1 _]     (observe tid (at-ms 1000))
          ;; Well within the 5-minute window
          now        (at-ms 60000)
          [s2 edges] (sut/expire-pending s1 now suppression-window-ms)]
      (is (empty? edges))
      (is (= s1 s2)))))

(deftest expire-pending-does-not-time-out-when-handler-has-started
  (testing "a long-running handler that started promptly is NOT eligible for timeout"
    (let [tid        (random-uuid)
          [s1 _ _]   (observe+start tid)
          ;; Way past the window — but the handler started, so the edge is
          ;; protected from the timeout arm per §2.4 third transition.
          now        (at-ms 999999)
          [s2 edges] (sut/expire-pending s1 now suppression-window-ms)]
      (is (empty? edges)
          "handler-started edges MUST NOT transition to :needs-operator via timeout")
      (is (= s1 s2)
          "state unchanged when no eligible edges expire")
      (is (contains? (:pending s2) tid)
          "pending edge stays put until the handler completes or fails"))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :needs-operator (no-handler arm)

(deftest observed-to-needs-operator-on-no-handler
  (testing "apply-no-handler transitions a pending edge and terminalizes"
    (let [tid       (random-uuid)
          [s1 _]    (observe tid (at-ms 1000))
          signal    {:routing/trigger-event-id tid
                     :event/timestamp          (at-ms 2000)}
          [s2 edge] (sut/apply-no-handler s1 signal)]
      (is (= :needs-operator (:edge/status edge)))
      (is (true? (:edge/operator-action-required edge)))
      (is (= (at-ms 2000) (:edge/updated-at edge)))
      (is (nil? (get-in s2 [:pending tid])))
      (is (= :needs-operator (:edge/status (get-in s2 [:terminal tid]))))
      (is (not (contains? edge :edge/fallback-intervention))
          "v1 omits :edge/fallback-intervention (placeholder not in vocabulary)"))))

;------------------------------------------------------------------------------ Layer 1
;; :observed → :suppressed (pending)

(deftest pending-to-suppressed-on-intervention
  (testing "apply-suppress on a pending edge transitions to :suppressed and terminalizes"
    (let [tid       (random-uuid)
          [s1 _]    (observe tid (at-ms 1000))
          interv    {:edge/trigger-event-id tid
                     :event/timestamp       (at-ms 2000)}
          [s2 edge] (sut/apply-suppress s1 interv)]
      (is (= :suppressed (:edge/status edge)))
      (is (false? (:edge/operator-action-required edge)))
      (is (= (at-ms 2000) (:edge/updated-at edge)))
      (is (nil? (get-in s2 [:pending tid])))
      (is (= :suppressed (:edge/status (get-in s2 [:terminal tid])))))))

;------------------------------------------------------------------------------ Layer 1
;; Terminal → :suppressed — operator may suppress an already-terminal edge

(deftest handled-to-suppressed-preserves-handled-workflow
  (testing "operator suppresses a :handled edge → :suppressed; workflow-run-id preserved"
    (let [tid          (random-uuid)
          [s1 _ wf]    (observe+start tid)
          [s2 _]       (sut/apply-workflow-completed
                         s1 (workflow-terminal wf (at-ms 2000)))
          interv       {:edge/trigger-event-id tid
                        :event/timestamp       (at-ms 3000)}
          [s3 edge]    (sut/apply-suppress s2 interv)]
      (is (= :suppressed (:edge/status edge)))
      (is (= wf (:edge/handled-by-workflow-run-id edge))
          ":edge/handled-by-workflow-run-id MUST survive post-terminal suppression")
      (is (= (at-ms 3000) (:edge/updated-at edge)))
      (is (= edge (get-in s3 [:terminal tid]))
          "the terminal entry is replaced with the :suppressed shape"))))

(deftest failed-to-suppressed-preserves-handled-workflow
  (testing "operator suppresses a :failed edge → :suppressed; workflow-run-id preserved"
    (let [tid          (random-uuid)
          [s1 _ wf]    (observe+start tid)
          [s2 _]       (sut/apply-workflow-failed
                         s1 (workflow-terminal wf (at-ms 2000)))
          [_  edge]    (sut/apply-suppress
                         s2 {:edge/trigger-event-id tid
                             :event/timestamp       (at-ms 3000)})]
      (is (= :suppressed (:edge/status edge)))
      (is (= wf (:edge/handled-by-workflow-run-id edge))))))

(deftest needs-operator-to-suppressed-after-expiry
  (testing "operator suppresses a :needs-operator edge (post-expiry) → :suppressed"
    (let [tid          (random-uuid)
          [s1 _]       (observe tid (at-ms 1000))
          [s2 _]       (sut/expire-pending s1 (at-ms 301001) suppression-window-ms)
          [_  edge]    (sut/apply-suppress
                         s2 {:edge/trigger-event-id tid
                             :event/timestamp       (at-ms 400000)})]
      (is (= :suppressed (:edge/status edge)))
      (is (= (at-ms 400000) (:edge/updated-at edge))))))

(deftest suppress-without-any-record-is-noop
  (testing "suppress for an unknown trigger-event-id leaves state unchanged"
    (let [orphan-tid  (random-uuid)
          interv      {:edge/trigger-event-id orphan-tid
                       :event/timestamp       (at-ms 2000)}
          [s2 edge]   (sut/apply-suppress sut/empty-state interv)]
      (is (nil? edge))
      (is (= sut/empty-state s2)))))

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
