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

(ns ai.miniforge.automation-edge-correlator.integration-test
  "End-to-end lifecycle tests for the AutomationEdge correlator —
   subscription wiring, emission, recursion prevention, cross-restart
   replay, and the expire-tick path (driven deterministically via
   `expire-once!`, not `Thread/sleep`).

   Uses an in-memory event stream with no sinks so test runs leave no
   on-disk debris."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.automation-edge-correlator.core :as core]
   [ai.miniforge.automation-edge-correlator.interface :as iface]
   [ai.miniforge.event-stream.core :as es-core]
   [ai.miniforge.event-stream.interface :as es])
  (:import
   (java.util Date)))

;------------------------------------------------------------------------------ Helpers

(defn- in-memory-stream
  []
  (es-core/create-event-stream {:sinks []}))

(defn- upserts
  "All `:supervisory/automation-edge-upserted` events on the stream, in
   publish order."
  [stream]
  (->> (es/get-events stream)
       (filter #(= :supervisory/automation-edge-upserted (:event/type %)))
       vec))

(defn- at-ms ^Date [^long ms] (Date. ms))

(defn- pr-merged-event
  ([] (pr-merged-event (random-uuid) (at-ms 1000)))
  ([event-id ts]
   {:event/type            :pr/merged
    :event/id              event-id
    :event/timestamp       ts
    :event/version         "1.0.0"
    :event/sequence-number 0
    :pr/repo               "miniforge-ai/miniforge"
    :pr/number             999
    :message               "merged"}))

(defn- workflow-started-event
  [workflow-id trigger-event-id]
  {:event/type               :workflow/started
   :event/id                 (random-uuid)
   :event/timestamp          (at-ms 1100)
   :event/version            "1.0.0"
   :event/sequence-number    0
   :workflow/id              workflow-id
   :routing/trigger-event-id trigger-event-id
   :message                  "started"})

(defn- workflow-completed-event
  [workflow-id]
  {:event/type            :workflow/completed
   :event/id              (random-uuid)
   :event/timestamp       (at-ms 1200)
   :event/version         "1.0.0"
   :event/sequence-number 0
   :workflow/id           workflow-id
   :message               "completed"})

(defn- fake-clock
  "A 0-arity fn returning the value of `t-atom`. Tests drive the clock
   forward via `(reset! t-atom new-date)` and invoke `core/expire-once!`
   by hand — no `Thread/sleep`, fully deterministic."
  [t-atom]
  (fn [] @t-atom))

(defn- await-upsert-count
  "Poll up to `budget-ms` for `(count (upserts stream))` to reach `n`.
   The subscriber callback runs synchronously under `publish!`, so this
   spin is essentially a one-iteration check in practice — kept defensive
   in case future executor changes make the callback async."
  [stream n budget-ms]
  (let [deadline (+ (System/currentTimeMillis) budget-ms)]
    (loop []
      (cond
        (>= (count (upserts stream)) n) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

;; Cadence override that prevents the scheduled tick from firing during
;; the tests — we drive `core/expire-once!` by hand. `Long/MAX_VALUE`
;; effectively means "never" without disabling the scheduler entirely
;; (which would change the `start!` code path).
(def ^:private never-fire-ms Long/MAX_VALUE)

(defn- start-with-clock
  "Boot a correlator handle bound to `stream` with the injected clock
   and a no-fire expire cadence so tests drive timing deterministically."
  [stream t-atom]
  (iface/attach! stream {:clock  (fake-clock t-atom)
                         :config {:correlator/suppression-window-ms 300000
                                  :correlator/expire-tick-ms        never-fire-ms}}))

;------------------------------------------------------------------------------ Tests

(deftest trigger-emits-observed-edge
  (testing "A `:pr/merged` trigger lands one `:observed` upsert on the stream."
    (let [stream (in-memory-stream)
          t-atom (atom (at-ms 1000))
          handle (start-with-clock stream t-atom)]
      (try
        (es/publish! stream (pr-merged-event))
        (is (await-upsert-count stream 1 500))
        (let [[ev] (upserts stream)]
          (is (= :supervisory/automation-edge-upserted (:event/type ev)))
          (is (= :observed (get-in ev [:supervisory/entity :edge/status])))
          (is (= :pr-merged (get-in ev [:supervisory/entity :edge/trigger-kind]))))
        (finally (iface/stop! handle))))))

(deftest workflow-started-emits-no-upsert
  (testing "`:workflow/started` indexes the workflow but emits no edge upsert."
    (let [stream  (in-memory-stream)
          t-atom  (atom (at-ms 1000))
          handle  (start-with-clock stream t-atom)
          trigger (pr-merged-event)]
      (try
        (es/publish! stream trigger)
        (await-upsert-count stream 1 500)
        (let [baseline (count (upserts stream))]
          (es/publish! stream (workflow-started-event (random-uuid) (:event/id trigger)))
          (Thread/sleep 20)
          (is (= baseline (count (upserts stream)))
              "workflow/started must not emit an upsert (handled-arm is a no-op for emission)"))
        (finally (iface/stop! handle))))))

(deftest workflow-completed-transitions-to-handled
  (testing "Observed → handled flow emits two upserts sharing one `:edge/id`."
    (let [stream      (in-memory-stream)
          t-atom      (atom (at-ms 1000))
          handle      (start-with-clock stream t-atom)
          trigger     (pr-merged-event)
          workflow-id (random-uuid)]
      (try
        (es/publish! stream trigger)
        (await-upsert-count stream 1 500)
        (es/publish! stream (workflow-started-event workflow-id (:event/id trigger)))
        (es/publish! stream (workflow-completed-event workflow-id))
        (is (await-upsert-count stream 2 500))
        (let [evs (upserts stream)
              [observed handled] evs]
          (is (= :observed (get-in observed [:supervisory/entity :edge/status])))
          (is (= :handled  (get-in handled  [:supervisory/entity :edge/status])))
          (is (= (get-in observed [:supervisory/entity :edge/id])
                 (get-in handled  [:supervisory/entity :edge/id]))
              "Both upserts share `:edge/id` so the consumer dedups to one row."))
        (finally (iface/stop! handle))))))

(deftest recursion-prevention-drops-self-emissions
  (testing "Synthetic `:supervisory/automation-edge-upserted` injected into the
            stream MUST NOT be fed back through the dispatch (no emission storm)."
    (let [stream (in-memory-stream)
          t-atom (atom (at-ms 1000))
          handle (start-with-clock stream t-atom)]
      (try
        (es/publish! stream {:event/type            :supervisory/automation-edge-upserted
                             :event/id              (random-uuid)
                             :event/timestamp       (at-ms 1000)
                             :event/version         "1.0.0"
                             :event/sequence-number 0
                             :supervisory/entity    {:edge/id            (random-uuid)
                                                     :edge/status        :observed
                                                     :edge/trigger-kind  :pr-merged}
                             :message               "synthetic"})
        (Thread/sleep 20)
        (is (= 1 (count (upserts stream)))
            "The injected event is the only upsert on the stream — the
             correlator must not have re-emitted in response.")
        (finally (iface/stop! handle))))))

(deftest replay-reconstructs-edges-byte-identically
  (testing "On a fresh `attach!` against the same stream prefix, the
            correlator reconstructs the same edges with byte-identical
            `:edge/id` and `:edge/status` per §3.6."
    (let [stream      (in-memory-stream)
          t-atom      (atom (at-ms 1000))
          trigger     (pr-merged-event)
          workflow-id (random-uuid)
          handle-1    (start-with-clock stream t-atom)]
      (try
        (es/publish! stream trigger)
        (es/publish! stream (workflow-started-event workflow-id (:event/id trigger)))
        (es/publish! stream (workflow-completed-event workflow-id))
        (await-upsert-count stream 2 500)
        (iface/stop! handle-1)

        (let [pre-restart-edges
              (mapv #(get % :supervisory/entity) (upserts stream))
              ;; Filter out the just-emitted upserts so they don't get
              ;; replayed (the recursion filter would skip them anyway,
              ;; but we want to assert the post-replay edges came from
              ;; folding the source events, not from a re-observation
              ;; of our prior emission).
              filtered-stream
              (let [s (in-memory-stream)]
                (doseq [e (es/get-events stream)
                        :when (not= :supervisory/automation-edge-upserted
                                    (:event/type e))]
                  (es/publish! s e))
                s)
              handle-2 (start-with-clock filtered-stream t-atom)]
          (try
            (await-upsert-count filtered-stream 2 500)
            (let [post-restart-edges
                  (mapv #(get % :supervisory/entity) (upserts filtered-stream))]
              (is (= (set (map :edge/id pre-restart-edges))
                     (set (map :edge/id post-restart-edges)))
                  "Edge ids reconstruct identically across restart (UUIDv5 is deterministic).")
              (is (= (set (map :edge/status pre-restart-edges))
                     (set (map :edge/status post-restart-edges)))
                  "Edge statuses reconstruct identically across restart."))
            (finally (iface/stop! handle-2))))
        (finally (iface/stop! handle-1))))))

(deftest expire-tick-transitions-to-needs-operator
  (testing "After the suppression window elapses with no handler-started,
            `expire-once!` transitions the edge to `:needs-operator` and
            emits one upsert."
    (let [stream  (in-memory-stream)
          ;; Trigger occurs at 1 s past epoch; suppression window is
          ;; 300 s in `start-with-clock` config; clock advances to 1 s
          ;; past that window so the edge is eligible.
          t-atom  (atom (at-ms 1000))
          handle  (start-with-clock stream t-atom)
          trigger (pr-merged-event (random-uuid) (at-ms 1000))]
      (try
        (es/publish! stream trigger)
        (await-upsert-count stream 1 500)

        ;; Advance clock past the suppression window and drive the
        ;; expire tick by hand — no Thread/sleep on the cadence.
        (reset! t-atom (at-ms (+ 1000 300000 1)))
        (let [expired (core/expire-once! handle)]
          (is (= 1 (count expired)))
          (is (= :needs-operator (:edge/status (first expired)))))

        (is (await-upsert-count stream 2 500))
        (let [[_ needs-op] (upserts stream)]
          (is (= :needs-operator
                 (get-in needs-op [:supervisory/entity :edge/status]))))
        (finally (iface/stop! handle))))))

(deftest expire-tick-skips-edges-with-started-handler
  (testing "An edge whose `:workflow/started` has been observed is NOT
            timed out by the expire-tick — handler-started protection."
    (let [stream      (in-memory-stream)
          t-atom      (atom (at-ms 1000))
          handle      (start-with-clock stream t-atom)
          trigger     (pr-merged-event (random-uuid) (at-ms 1000))
          workflow-id (random-uuid)]
      (try
        (es/publish! stream trigger)
        (es/publish! stream (workflow-started-event workflow-id (:event/id trigger)))
        (await-upsert-count stream 1 500)
        (reset! t-atom (at-ms (+ 1000 300000 1)))
        (let [expired (core/expire-once! handle)]
          (is (empty? expired)
              "Handler workflow already started — edge stays pending
               regardless of age."))
        (finally (iface/stop! handle))))))

(deftest new-trigger-event-types-emit-observed-edges
  (testing "N15-5 trigger constructors flow through the correlator and open
            edges with the right :edge/trigger-kind."
    (let [stream (in-memory-stream)
          t-atom (atom (at-ms 1000))
          handle (start-with-clock stream t-atom)]
      (try
        (es/publish! stream (es/pr-monitor-review-comments-arrived
                             stream "miniforge-ai/miniforge" 999 3))
        (es/publish! stream (es/pr-monitor-ci-failed
                             stream "miniforge-ai/miniforge" 999 "tests" :failure))
        (es/publish! stream (es/standards-review-posted
                             stream "miniforge-ai/miniforge" 999 :advisory))
        (is (await-upsert-count stream 3 500))
        (let [edges (->> (upserts stream)
                         (map :supervisory/entity)
                         (sort-by :edge/trigger-kind))]
          (is (= [:ci-failed :review-comments-arrived :standards-review-arrived]
                 (mapv :edge/trigger-kind edges))
              "Each new trigger event type MUST classify to its matching RoutingTriggerKind and surface as an :observed upsert. Three events in, three edges out.")
          (is (every? #(= :observed %) (map :edge/status edges))
              "All three open as :observed — handler signals come later.")
          (is (every? #(seq (:edge/affected-pr-ids %)) edges)
              "Each constructor carries :pr/repo + :pr/number; the correlator's §3.4 payload-derivation lifts a single [repo number] tuple onto :edge/affected-pr-ids."))
        (finally (iface/stop! handle))))))
