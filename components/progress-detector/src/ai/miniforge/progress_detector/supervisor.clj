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

(ns ai.miniforge.progress-detector.supervisor
  "Minimal supervisor — consumes detector-emitted anomalies and decides
   whether to terminate the agent run.

   Stage 2 ships a class-default policy:

     :anomaly/class :mechanical → :terminate
     :anomaly/class :heuristic  → :warn (logged, no action)

   Stage 3 adds a per-category on-anomaly map for finer-grained control.
   Resolution order in the 3-arity handle:

     1. category lookup in on-anomaly  (e.g. {:tool-loop :terminate})
     2. class-default from policy       (e.g. {:mechanical :terminate})
     3. :continue                       (catch-all safe default)

   Controlling-anomaly selection (when multiple anomalies fire in
   the same observe step):

     :fatal > :error > :warn > :info        (severity rank)
     ties broken: :mechanical > :heuristic  (class rank)
     further ties: earliest :event/seq      (deterministic)

   All anomalies (controlling and not) are surfaced to the caller;
   the supervisor names which one drives the decision."
  (:require
   [ai.miniforge.progress-detector.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Severity + class ranking

(def ^:private severity-rank
  "Higher number = stronger signal. Used to choose the controlling
   anomaly when multiple fire in the same observe step."
  {:fatal 4 :error 3 :warn 2 :info 1})

(def ^:private class-rank
  "Mechanical > heuristic when severities tie."
  {:mechanical 2 :heuristic 1})

(def default-policy
  "Stage 2 class-default policy. Stage 3 layers a per-category map
   on top of this."
  {:mechanical :terminate
   :heuristic  :warn})

;------------------------------------------------------------------------------ Layer 1
;; Anomaly accessors (data lives under :anomaly/data per Stage 1 schema)

(defn- anomaly-severity [a] (get-in a [:anomaly/data :anomaly/severity]))
(defn- anomaly-class    [a] (get-in a [:anomaly/data :anomaly/class]))
(defn- anomaly-category [a] (get-in a [:anomaly/data :anomaly/category]))

(defn- anomaly-event-seq
  "First :seq from :anomaly/data :anomaly/evidence :event-ids — used as
   the final tie-breaker. Missing values sort to a high sentinel so
   anomalies WITH event-ids always beat anomalies without."
  [a]
  (or (first (get-in a [:anomaly/data :anomaly/evidence :event-ids]))
      Long/MAX_VALUE))

(defn- ranking-tuple
  "Tuple used to order anomalies for controlling-anomaly selection.
   Negative severity/class so that `compare` treats the strongest
   signal as smallest (sorts to head). :event/seq stays positive so
   earlier wins on ties."
  [a]
  [(- (get severity-rank (anomaly-severity a) 0))
   (- (get class-rank    (anomaly-class    a) 0))
   (anomaly-event-seq a)])

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn select-controlling
  "Choose one controlling anomaly from a non-empty seq.

   Selection rule (Stage 1 spec, lifted verbatim):
     :fatal > :error > :warn > :info,
     ties broken :mechanical > :heuristic,
     further ties broken by earliest :event/seq.

   Returns nil for an empty input."
  [anomalies]
  (when (seq anomalies)
    (first (sort-by ranking-tuple anomalies))))

(defn handle
  "Decide what to do given a vector of anomalies.

   Arities:

     (handle anomalies)
       1-arity convenience — uses default-policy and empty on-anomaly.

     (handle policy anomalies)
       2-arity back-compat — delegates to (handle policy {} anomalies).

     (handle policy on-anomaly anomalies)
       3-arity canonical form. Arguments:
         policy     - map of :anomaly/class → action keyword
                      (default: default-policy = mechanical→:terminate,
                                                 heuristic→:warn)
         on-anomaly - map of :anomaly/category → action keyword,
                      consulted BEFORE the class-default policy.
                      Example: {:anomalies.agent/tool-loop :terminate
                                :anomalies.review/stagnation :continue}
         anomalies  - vector of anomaly maps emitted by the detector pipeline

   Resolution order for action:
     1. (get on-anomaly (anomaly-category controlling))
     2. (get policy     (anomaly-class    controlling))
     3. :continue

   Returns:
     {:action     :continue | :terminate | :warn
      :anomalies  vector — all anomalies, controlling + non-controlling
      :anomaly    the controlling anomaly map (only when non-empty input)
      :reason     human-readable termination reason (only when :terminate)}

   The result is data — no side effects. The caller (typically
   runtime.clj wired into agent.invoke) drives any actual cancellation."
  ([anomalies]           (handle default-policy {} anomalies))
  ([policy anomalies]    (handle policy {} anomalies))
  ([policy on-anomaly anomalies]
   (if (empty? anomalies)
     {:action    :continue
      :anomalies anomalies}
     (let [controlling (select-controlling anomalies)
           category    (anomaly-category controlling)
           action      (or (get on-anomaly category)
                           (get policy (anomaly-class controlling))
                           :continue)
           summary     (get-in controlling
                               [:anomaly/data :anomaly/evidence :summary]
                               (msg/t :supervisor/no-summary))
           reason      (msg/t :supervisor/terminate-reason {:summary summary})]
       (cond-> {:action    action
                :anomalies anomalies
                :anomaly   controlling}
         (= action :terminate)
         (assoc :reason reason))))))

(defn terminate?
  "Convenience predicate over a `handle` decision map."
  [decision]
  (= :terminate (:action decision)))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Empty input — pass through
  (handle [])
  ;; => {:action :continue :anomalies []}

  ;; A mechanical error → terminate (1-arity, uses default-policy)
  (def err-anomaly
    {:anomaly/type :fault
     :anomaly/data {:detector/kind    :detector/tool-loop
                    :anomaly/class    :mechanical
                    :anomaly/category :anomalies.agent/tool-loop
                    :anomaly/severity :error
                    :anomaly/evidence {:summary "Read foo.clj 6 times"
                                       :event-ids [1]}}})
  (handle [err-anomaly])
  ;; => {:action :terminate :anomaly {...} :reason "Terminating run: ..."}

  ;; 3-arity: category overrides class-default
  ;; on-anomaly says :tool-loop → :continue → no termination even though
  ;; the class-default policy would say :terminate
  (handle default-policy
          {:anomalies.agent/tool-loop :continue}
          [err-anomaly])
  ;; => {:action :continue ...}

  ;; Heuristic warn → don't terminate
  (def warn-anomaly
    (-> err-anomaly
        (assoc-in [:anomaly/data :anomaly/class] :heuristic)
        (assoc-in [:anomaly/data :anomaly/severity] :warn)))
  (handle [warn-anomaly])
  ;; => {:action :warn :anomaly {...}}

  ;; Multiple — fatal beats error beats warn
  (handle [warn-anomaly err-anomaly])
  ;; => :anomaly is err-anomaly (severity :error)

  :leave-this-here)
