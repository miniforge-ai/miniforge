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

(ns ai.miniforge.pr-scoring.core
  "PR scoring component — produces `:pr/scored` events per N5-delta-2 §3.

   Subscribes to fine-grained PR-mutating events on the miniforge event
   stream, invokes a pluggable *scorer function* for each incoming PR
   event, and emits a `:pr/scored` event when the scorer returns
   non-nil scores. The emitted event is consumed exclusively by
   `supervisory-state`, which merges the four score fields into the
   canonical `PrFleetEntry` per N5-delta-2 §3.2.

   This component owns the **subscription, trigger, and emission**
   plumbing. The actual scoring math lives in `pr-train.readiness`,
   `pr-train.risk`, and `policy-pack.external` — the scorer-fn injected
   at `create` time is expected to call into those libraries and
   assemble the `{:readiness :risk :policy :recommendation}` return
   map (any subset MAY be omitted per N5-delta-2 §5.4).

   ## Scorer-fn contract

       (scorer-fn pr-event) => {:readiness {...}
                                :risk {...}
                                :policy {...}
                                :recommendation :merge} ;; or nil

   When the scorer returns `nil`, no `:pr/scored` event is emitted —
   this lets the scorer choose to skip PRs it cannot score yet (e.g.
   missing diff, train context not built).

   ## Rationale for placement

   N5-delta-1 §3.4 invariant 6 requires `supervisory-state` to be the
   sole emitter of `:supervisory/pr-upserted`. Scoring therefore cannot
   happen inside `supervisory-state` (would add `pr-train` +
   `policy-pack` deps to the projection component) and cannot emit
   snapshot events directly. Instead, scoring lives here, emits only a
   fine-grained `:pr/scored` event, and `supervisory-state` coalesces
   scored fields into its next upsert emission."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.interface.events :as events]))

;------------------------------------------------------------------------------ Layer 0
;; Subscription key

(def ^:private subscriber-id ::pr-scoring)

;------------------------------------------------------------------------------ Layer 0
;; Trigger events — loaded from data

(def trigger-config-resource
  "Classpath location of the default trigger-event-types EDN set."
  "config/pr-scoring/triggers.edn")

(defn load-default-triggers
  "Read the default trigger-event-types set from the resource at
   [[trigger-config-resource]]. Callers MAY override at component
   construction time via the `:trigger-event-types` option to [[create]]
   — the config is data, not compiled-in logic."
  []
  (if-let [r (io/resource trigger-config-resource)]
    (edn/read-string (slurp r))
    (throw (ex-info "pr-scoring: missing trigger-event-types config resource"
                    {:resource trigger-config-resource}))))

(def default-trigger-event-types
  "Memoized default trigger set loaded from [[trigger-config-resource]].
   Delayed so classpath scan happens on first use, not namespace load."
  (delay (load-default-triggers)))

;------------------------------------------------------------------------------ Layer 1
;; Default scorer (no-op)

(defn default-scorer-fn
  "Placeholder scorer — returns `nil` so no `:pr/scored` events are
   emitted. Replace with a real scorer at `create` time once the
   `pr-train` + `policy-pack` integration lands."
  [_pr-event]
  nil)

;------------------------------------------------------------------------------ Layer 2
;; Event handling

(defn- emit-scored!
  "Call `scorer-fn` on `event` and, if scores are returned, publish a
   `:pr/scored` event on `stream` carrying them."
  [stream scorer-fn event]
  (when-let [scores (scorer-fn event)]
    (es/publish! stream
                 (events/pr-scored stream
                                   (:pr/repo event)
                                   (:pr/number event)
                                   scores
                                   (select-keys event [:workflow/id])))))

(defn- handle-event!
  "Entry point for every event the stream delivers. No-op unless the
   event type is in `triggers`. A scorer-fn that throws is suppressed
   silently (for the scaffold) — a follow-up PR wires proper structured
   logging once the real scoring integration lands."
  [triggers scorer-fn stream event]
  (when (contains? triggers (:event/type event))
    (try
      (emit-scored! stream scorer-fn event)
      (catch Throwable _t nil))))

;------------------------------------------------------------------------------ Layer 3
;; Lifecycle

(defn create
  "Build a fresh pr-scoring component instance bound to `stream`. Does
   not subscribe yet — call `start!` to begin consuming events.

   Options:
     :scorer-fn           — 1-ary fn (event → score-map or nil); defaults
                            to [[default-scorer-fn]] which emits nothing
     :trigger-event-types — set of event-type keywords that trigger
                            scoring; defaults to the EDN set at
                            [[trigger-config-resource]]"
  ([stream] (create stream {}))
  ([stream {:keys [scorer-fn trigger-event-types]
            :or   {scorer-fn default-scorer-fn}}]
   (atom {:stream stream
          :scorer-fn scorer-fn
          :triggers (or trigger-event-types @default-trigger-event-types)
          :subscribed? false})))

(defn start!
  "Subscribe to the stream. Idempotent — repeat calls are no-ops."
  [component]
  (let [{:keys [stream scorer-fn triggers subscribed?]} @component]
    (when-not subscribed?
      (es/subscribe! stream subscriber-id
                     (fn [event] (handle-event! triggers scorer-fn stream event)))
      (swap! component assoc :subscribed? true))
    component))

(defn stop!
  "Unsubscribe. Idempotent."
  [component]
  (let [{:keys [stream subscribed?]} @component]
    (when subscribed?
      (es/unsubscribe! stream subscriber-id)
      (swap! component assoc :subscribed? false))
    component))

(defn attach!
  "Create + start in one step."
  ([stream] (attach! stream {}))
  ([stream opts] (start! (create stream opts))))
