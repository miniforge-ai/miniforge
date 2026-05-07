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

(ns ai.miniforge.progress-detector.runtime
  "Agent-run detector pipeline — the binding between detectors,
   supervisor, and the agent's tool/event stream.

   Single point of integration for the agent layer. One runtime
   instance lives for the duration of one agent invocation.
   Observations stream in via observe!; the supervisor's decision
   is queried via check.

   The runtime owns:
     - a single MultiDetector composing the configured detector set
     - the running detector state (one map; pure-reducer threading)
     - the supervisor policy (class-default decisions)
     - the on-anomaly map (per-category action overrides, consulted
       before the class-default policy)
     - bookkeeping for which anomalies have already been seen by
       the supervisor (so we don't re-fire termination on the same
       anomaly across observe! calls)

   Concurrency: not safe for cross-thread observe! calls on the
   same runtime. Agent invocations are single-threaded per run, so
   this is fine. The atom is for state-threading sugar, not
   concurrency."
  (:require
   [ai.miniforge.progress-detector.protocol   :as proto]
   [ai.miniforge.progress-detector.supervisor :as sup]))

;------------------------------------------------------------------------------ Layer 0
;; State shape

(defn- initial-state
  "Produce the initial atom value for a runtime.

   Keys:
     :detector       - the composed (Multi)Detector
     :detector-state - the threaded reducer state
     :policy         - supervisor class-default policy map
     :on-anomaly     - per-category action override map; consulted
                       before :policy in the 3-arity supervisor/handle
     :seen-count     - number of anomalies the supervisor has been
                       shown so far; new-anomalies = drop seen-count
                       from current-anomalies"
  [detector detector-state policy on-anomaly]
  {:detector       detector
   :detector-state detector-state
   :policy         policy
   :on-anomaly     on-anomaly
   :seen-count     0})

;------------------------------------------------------------------------------ Layer 1
;; Construction

(defn- effective-detectors
  "Resolve the configured detector list. An empty list is valid —
   the spec acceptance requires that disabling all detectors leaves
   the runtime functional, so we substitute a single null-detector
   that observes nothing and never emits anomalies."
  [detectors]
  (if (empty? detectors)
    [(proto/null-detector)]
    detectors))

(defn make-runtime
  "Build a per-agent-run detector runtime.

   Arguments:
     opts - map with:
       :detectors  - seq of Detector implementations. May be empty —
                     an empty list is replaced with a single
                     null-detector so the runtime stays usable when
                     detectors are disabled (Stage 2 spec acceptance:
                     'removing all detectors leaves the agent runtime
                     functional and bounded by Layer 1 caps').
       :config     - (optional) detector config map passed to init
                     on each detector
       :policy     - (optional) supervisor class-default policy map
                     (defaults to sup/default-policy)
       :on-anomaly - (optional) per-category action override map.
                     Consulted before :policy in supervisor/handle.
                     Example: {:anomalies.agent/tool-loop :terminate
                               :anomalies.review/stagnation :continue}
                     Defaults to {} (no category overrides).

   Returns: atom holding runtime state. Pass to observe! / check
   to drive it."
  [{:keys [detectors config policy on-anomaly]
    :or   {config {} policy sup/default-policy on-anomaly {}}}]
  (let [composed (proto/multi-detector (effective-detectors detectors))
        d-state  (proto/init composed config)]
    (atom (initial-state composed d-state policy on-anomaly))))

;------------------------------------------------------------------------------ Layer 1
;; Observation

(defn- step-detector-state
  "Single reducer step over the runtime state map: thread one event
   through the composed detector and return the updated state."
  [state event]
  (let [det     (:detector state)
        d-state (proto/observe det (:detector-state state) event)]
    (assoc state :detector-state d-state)))

(defn observe!
  "Feed one event into the runtime's detector pipeline. Returns the
   runtime atom for chaining. The detector reducer state is threaded
   internally; callers do not see it."
  [runtime event]
  (swap! runtime step-detector-state event)
  runtime)

;------------------------------------------------------------------------------ Layer 2
;; Inspection — supervisor decision over current state

(defn current-anomalies
  "Vector of every anomaly the detector pipeline has emitted so far
   on this runtime, in emission order."
  [runtime]
  (get-in @runtime [:detector-state :anomalies] []))

(defn new-anomalies
  "Anomalies emitted since the last call to `check` (or since runtime
   creation if check has never been called). Useful for callers that
   want to react event-by-event."
  [runtime]
  (let [{:keys [detector-state seen-count]} @runtime
        all (get detector-state :anomalies [])]
    (subvec all (min seen-count (count all)))))

(defn check
  "Run the supervisor over anomalies emitted since the last check
   and return its decision map (see supervisor/handle).

   Threads both :policy (class-default) and :on-anomaly (per-category
   overrides) from the runtime config through to supervisor/handle
   3-arity, so category-specific actions take precedence over the
   class-default.

   Side effect: marks those anomalies as seen so the next check
   only considers anomalies that arrived after this one. This
   guards against repeatedly returning :terminate for the same
   already-acted-on anomaly while the runtime is still draining."
  [runtime]
  (let [{:keys [detector-state policy on-anomaly seen-count]} @runtime
        all       (get detector-state :anomalies [])
        unseen    (subvec all (min seen-count (count all)))
        decision  (sup/handle policy on-anomaly unseen)]
    (swap! runtime assoc :seen-count (count all))
    decision))

(defn terminate?
  "Convenience: peek at the supervisor decision without consuming
   the unseen-anomaly window. Use `check` when you want to mark
   anomalies as acted-on."
  [runtime]
  (let [{:keys [detector-state policy on-anomaly seen-count]} @runtime
        all    (get detector-state :anomalies [])
        unseen (subvec all (min seen-count (count all)))]
    (sup/terminate? (sup/handle policy on-anomaly unseen))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; End-to-end smoke
  (require '[ai.miniforge.progress-detector.detectors.tool-loop :as tl]
           '[ai.miniforge.progress-detector.detectors.repair-loop :as rl]
           '[ai.miniforge.progress-detector.tool-profile :as tp])

  (def reg (tp/make-registry))
  (tp/register! reg
                {:tool/id     :tool/Read
                 :determinism :stable-with-resource-version
                 :anomaly/categories #{:anomalies.agent/tool-loop}})

  ;; Default runtime — mechanical anomaly terminates
  (def rt (make-runtime
            {:detectors [(tl/make-tool-loop-detector reg)
                         (rl/make-repair-loop-detector)]
             :config    {:config/params {:threshold-n 3}}}))

  ;; Runtime with on-anomaly: tool-loop → :continue (suppressed)
  ;; but stagnation → :terminate (default policy)
  (def rt2 (make-runtime
             {:detectors [(tl/make-tool-loop-detector reg)
                          (rl/make-repair-loop-detector)]
              :config    {:config/params {:threshold-n 3}}
              :on-anomaly {:anomalies.agent/tool-loop :continue}}))

  ;; Hammer Read with the same args 3× — should fire mechanical
  (let [obs {:tool/id   :tool/Read
             :seq       1
             :timestamp (java.time.Instant/now)
             :tool/input            "src/foo.clj"
             :resource/version-hash "sha256:aaa"}]
    (observe! rt (assoc obs :seq 1))
    (observe! rt (assoc obs :seq 2))
    (observe! rt (assoc obs :seq 3)))

  (check rt)
  ;; => {:action :terminate :anomaly {...} :reason "..."}

  :leave-this-here)
