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
(ns ai.miniforge.pr-scoring.interface
  "Public API for the pr-scoring component (N5-delta-2 §3).

   Produces `:pr/scored` events by invoking a pluggable scorer-fn on
   fine-grained PR events. Consumed exclusively by `supervisory-state`,
   which merges the scored fields into the canonical `PrFleetEntry`.

   Typical use:

       (require '[ai.miniforge.event-stream.interface :as es]
                '[ai.miniforge.pr-scoring.interface :as scoring])

       (def stream  (es/create-event-stream))
       (def scorer  (scoring/attach! stream {:scorer-fn my-scorer}))
       ;; … PR events flow through the stream; :pr/scored emitted
       ;;   whenever my-scorer returns non-nil …
       (scoring/stop! scorer)"
  (:require
   [ai.miniforge.pr-scoring.core :as core]))

;------------------------------------------------------------------------------ Layer 0

;; Lifecycle
(def ^{:stratum 0} create
  "Build a fresh pr-scoring component instance bound to event stream `stream`.
   Does not subscribe yet — call [[start!]] to begin consuming events.

   Args: `stream`, and an optional opts map:
     :scorer-fn           — 1-ary fn (pr-event → score-map or nil); defaults
                            to [[default-scorer-fn]] (emits nothing)
     :trigger-event-types — set of event-type keywords that trigger scoring;
                            defaults to the EDN set at [[trigger-config-resource]]

   Returns an atom holding the component state map
   {:stream :scorer-fn :triggers :subscribed?}."
  core/create)

(def ^{:stratum 0} start!
  "Subscribe the component to its stream so events begin flowing to the
   scorer-fn. Idempotent — repeat calls are no-ops.
   Args: `component` (the atom from [[create]]). Returns that same atom."
  core/start!)

(def ^{:stratum 0} stop!
  "Unsubscribe the component from its stream. Idempotent — no-op if not
   currently subscribed.
   Args: `component` (the atom). Returns that same atom."
  core/stop!)

(def ^{:stratum 0} attach!
  "Create + start in one step.
   Args: `stream`, and an optional opts map (see [[create]]).
   Returns the started component atom."
  core/attach!)

;; Extension points
(def ^{:stratum 0} default-scorer-fn
  "Placeholder scorer used when no `:scorer-fn` is supplied to [[create]].
   1-ary fn (pr-event → nil); always returns nil, so no `:pr/scored`
   events are emitted. Replace with a real scorer at create time."
  core/default-scorer-fn)

(def ^{:stratum 0} load-default-triggers
  "Read the default trigger-event-types set from the classpath resource at
   [[trigger-config-resource]]. Takes no args. Returns a set of event-type
   keywords. Throws ex-info if the resource is missing from the classpath."
  core/load-default-triggers)

(def ^{:stratum 0} default-trigger-event-types
  "A delay wrapping the default trigger set (see [[load-default-triggers]]);
   deref to obtain the set of event-type keywords. Delayed so the classpath
   scan runs on first use rather than at namespace load."
  core/default-trigger-event-types)

(def ^{:stratum 0} trigger-config-resource
  "String: classpath location of the default trigger-event-types EDN set."
  core/trigger-config-resource)
