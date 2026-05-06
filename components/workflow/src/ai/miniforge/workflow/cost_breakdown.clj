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

(ns ai.miniforge.workflow.cost-breakdown
  "Canonical cost-breakdown shape for v2 multi-parent merge telemetry,
   per `specs/informative/I-DAG-MULTI-PARENT-MERGE.md` §3.5.

   The motivation. Before this namespace, miniforge tracked workflow
   cost as a flat `{:tokens N :cost-usd N :duration-ms N}` map per
   result. That answers 'how much did this run cost in total?' but not
   'where did the cost come from?' — which is the operator-actionable
   question per the round-2 user direction:

   > 'We want to have telemetry that allows us accounting to extract
   >  places to optimize (performance monitoring). Preserve the
   >  components, it is how we can see where to place our attention on
   >  performance and cost improvements.'

   Stage 4b ships the SHAPE and the helpers. Stage 2C (real LLM agent)
   and Stage 4c (dogfood re-run) will populate it from real measurements;
   the shape exists now so downstream code has a stable target to write
   to.

   The canonical shape (extends spec §3.5 — see notes below):

       {:cost/total      <tokens>            ; spec §3.5 — rolled up
        :cost/breakdown  {:task/explore           N    ; spec §3.5 — per-phase
                          :task/plan              M
                          :task/implement         K
                          :task/verify            L
                          :task/merge-resolution  P
                          :task/release           Q}
        :cost/iterations {:task/implement         I-implement   ; spec §3.5 — only
                          :task/merge-resolution  I-resolution} ; for iterating phases
        :cost/duration-ms <ms>                                  ; extension: wall-clock
        :cost/usd         <decimal>}                            ; extension: estimated $

   Spec §3.5 defines `:cost/total`, `:cost/breakdown`, and
   `:cost/iterations`. `:cost/duration-ms` and `:cost/usd` are
   extensions added here because the existing zero-metrics shape
   already tracked them and dropping them at the v2 boundary would
   regress observability. If the spec is updated later to formalize
   these keys, this docstring should drop the 'extension' wording.

   `:cost/iterations` is restricted to phases that have an iteration
   loop (`:task/implement` and `:task/merge-resolution` per spec §3.5).
   Other phases either run a single shot (`:task/release`) or have
   their iteration semantics tracked elsewhere (e.g., `:task/verify`'s
   retry budget). Adding iteration counts for non-iterating phases is
   a programming error and `add-phase-cost` rejects it.

   Granular components are preserved on purpose — rolling them up into
   aggregates loses the placement-of-attention signal that's the whole
   reason for collecting the data."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Schema

(def phase-key-order
  "Ordered vector of `:task/*` phase keys. Used for deterministic
   error-message rendering (sets have no iteration order across runs;
   `(pr-str some-set)` would print differently in different
   invocations and pollute logs). Membership checks use the derived
   `phase-keys` set."
  [:task/explore
   :task/plan
   :task/implement
   :task/verify
   :task/merge-resolution
   :task/release])

(def phase-keys
  "Set of all valid phase keys for the breakdown. Adding a new phase
   requires adding it here AND to `phase-key-order` so consumers see
   one complete vocabulary; `:closed`-schema validation will reject
   unknown keys at the boundary."
  (set phase-key-order))

(def iteration-phase-keys
  "Subset of `phase-keys` whose phases run an iteration loop. Per spec
   §3.5, `:cost/iterations` is keyed only by these. Adding iteration
   counts for non-iterating phases is a programming error caught by
   `add-phase-cost` and the schema."
  #{:task/implement
    :task/merge-resolution})

(defn- non-negative-finite?
  "True when `x` is a non-negative finite number (no NaN / Infinity).
   Used for `:cost/usd` since cost is always ≥ 0 and a NaN getting
   into the dashboard rendering would corrupt the summary."
  [x]
  (and (number? x)
       (not (Double/isNaN (double x)))
       (not (Double/isInfinite (double x)))
       (>= (double x) 0.0)))

(def CostBreakdown
  "Malli schema for the canonical cost-breakdown shape. Closed maps so
   typos / unknown keys fail validation rather than silently slip
   through into the telemetry pipeline.

   `:cost/iterations` keys are restricted to the iteration-phase
   subset (spec §3.5) so a verify or release phase's iteration count
   can't sneak in by mistake."
  [:map {:closed true}
   [:cost/total       {:default 0}    [:int {:min 0}]]
   [:cost/breakdown   {:default {}}   [:map-of (into [:enum] phase-key-order)
                                       [:int {:min 0}]]]
   [:cost/iterations  {:default {}}   [:map-of (into [:enum] (sort iteration-phase-keys))
                                       [:int {:min 0}]]]
   [:cost/duration-ms {:default 0}    [:int {:min 0}]]
   [:cost/usd         {:default 0.0}  [:fn {:error/message "must be a non-negative finite number"}
                                       non-negative-finite?]]])

(defn valid?
  "True when `value` matches `CostBreakdown`."
  [value]
  (m/validate CostBreakdown value))

(defn explain
  "Humanized explanation for an invalid breakdown, or nil when valid."
  [value]
  (some-> (m/explain CostBreakdown value)
          me/humanize))

;------------------------------------------------------------------------------ Layer 1
;; Constructors

(defn empty-breakdown
  "An all-zero breakdown. Used as the identity element for accumulation
   — `(reduce add-phase-cost (empty-breakdown) phase-results)` rolls up
   a sequence of phase results into one breakdown."
  []
  {:cost/total       0
   :cost/breakdown   {}
   :cost/iterations  {}
   :cost/duration-ms 0
   :cost/usd         0.0})

(defn add-phase-cost
  "Add `phase-result` into `breakdown`. `phase-result` is a map with
   any subset of:

   - `:phase`           — required keyword from `phase-keys`
   - `:tokens`          — optional non-negative integer
   - `:iterations`      — optional non-negative integer; rejected for
                          phases not in `iteration-phase-keys`
   - `:duration-ms`     — optional non-negative integer
   - `:usd`             — optional non-negative finite number

   Tokens roll into both `:cost/breakdown` (per-phase) and `:cost/total`
   (sum). Iterations are per-phase only — a per-phase total is
   meaningful (`how many iterations did the implement loop need?`) but
   a cross-phase sum isn't (`5 implement iterations + 2 verify
   iterations = 7 of what?`).

   Throws on unknown phase or iterations-on-non-iteration-phase. Both
   are programming errors at telemetry-emitter sites; failing here
   surfaces them at the call site rather than at downstream
   schema-validation time."
  [breakdown {:keys [phase tokens iterations duration-ms usd]
              :or   {tokens 0 iterations 0 duration-ms 0 usd 0.0}}]
  (when-not (contains? phase-keys phase)
    (throw (ex-info (str "Unknown phase " (pr-str phase) " — must be one of "
                         (pr-str phase-key-order))
                    {:phase phase :known phase-key-order})))
  (when (and (pos? iterations)
             (not (contains? iteration-phase-keys phase)))
    (throw (ex-info (str "Phase " (pr-str phase) " does not iterate; "
                         ":iterations may only be set for "
                         (pr-str (sort iteration-phase-keys)))
                    {:phase phase :iterations iterations
                     :allowed (sort iteration-phase-keys)})))
  (-> breakdown
      (update :cost/total + tokens)
      (update-in [:cost/breakdown phase] (fnil + 0) tokens)
      (cond->
        (pos? iterations)
        (update-in [:cost/iterations phase] (fnil + 0) iterations))
      (update :cost/duration-ms + duration-ms)
      (update :cost/usd + usd)))

(defn merge-breakdowns
  "Combine two breakdowns. Used at workflow boundaries where two
   sub-results' cost reports need to roll up into the parent's cost
   report (e.g. a sub-workflow's breakdown rolls into its parent
   task's breakdown).

   Adds totals and per-phase entries; never overwrites. Iteration
   counts add too — if both inputs ran an `:implement` iteration the
   merged breakdown shows the sum, which matches what an operator
   reading the dashboard wants ('total implement iterations across
   the run')."
  [a b]
  {:cost/total       (+ (:cost/total a 0) (:cost/total b 0))
   :cost/breakdown   (merge-with + (:cost/breakdown a {}) (:cost/breakdown b {}))
   :cost/iterations  (merge-with + (:cost/iterations a {}) (:cost/iterations b {}))
   :cost/duration-ms (+ (:cost/duration-ms a 0) (:cost/duration-ms b 0))
   :cost/usd         (+ (:cost/usd a 0.0) (:cost/usd b 0.0))})

;------------------------------------------------------------------------------ Layer 2
;; Read accessors — kept thin so callers don't bind to the map shape
;; outside this namespace.

(defn total-tokens
  "Total tokens consumed across all phases."
  [breakdown]
  (:cost/total breakdown 0))

(defn phase-tokens
  "Tokens consumed by `phase`, or 0 if the phase wasn't seen."
  [breakdown phase]
  (get-in breakdown [:cost/breakdown phase] 0))

(defn phase-iterations
  "Iterations the `phase` loop ran, or 0 if the phase wasn't seen."
  [breakdown phase]
  (get-in breakdown [:cost/iterations phase] 0))

(defn dominant-phase
  "Phase with the largest token cost. Returns nil for an empty
   breakdown. Lets the dashboard answer 'where is most of the cost
   coming from?' without forcing every consumer to walk the breakdown
   map."
  [breakdown]
  (let [b (:cost/breakdown breakdown {})]
    (when (seq b)
      (key (apply max-key val b)))))

;------------------------------------------------------------------------------ Rich Comment
(comment

  (-> (empty-breakdown)
      (add-phase-cost {:phase :task/implement :tokens 1200 :iterations 3 :duration-ms 8500})
      (add-phase-cost {:phase :task/verify    :tokens 200  :duration-ms 2200})
      (add-phase-cost {:phase :task/merge-resolution :tokens 600 :iterations 2 :duration-ms 4100}))
  ;; => {:cost/total 2000
  ;;     :cost/breakdown {:task/implement 1200 :task/verify 200 :task/merge-resolution 600}
  ;;     :cost/iterations {:task/implement 3 :task/merge-resolution 2}
  ;;     :cost/duration-ms 14800
  ;;     :cost/usd 0.0}

  (dominant-phase
   {:cost/breakdown {:task/implement 1200 :task/verify 200 :task/merge-resolution 600}})
  ;; => :task/implement

  (merge-breakdowns
   (-> (empty-breakdown)
       (add-phase-cost {:phase :task/implement :tokens 1000 :iterations 2}))
   (-> (empty-breakdown)
       (add-phase-cost {:phase :task/implement :tokens  500 :iterations 1})))
  ;; => {:cost/total 1500 :cost/breakdown {:task/implement 1500} ...}

  :leave-this-here)
