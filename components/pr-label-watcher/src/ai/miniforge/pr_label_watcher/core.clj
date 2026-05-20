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

(ns ai.miniforge.pr-label-watcher.core
  "Mechanical (zero-token) label → action matcher (M2 of the labeled-PR-
   actions plan — see `docs/design/labeled-pr-actions-plan.md`).

   Given a `:pr/merged` event whose labels intersect the configured
   registry, this matcher:

     1. Looks up the matching action(s) in the registry.
     2. Joins against the supplied active-workflows seq + each
        workflow's `:workflow/base-sha`.
     3. Applies the action's `:applies-when` predicate (currently only
        `:workflow.base-sha/not-ancestor-of-merge` — workflow predates
        the fix and should pick it up).
     4. Emits a structured match payload per matching label, naming
        every workflow that qualifies. Silent on miss.

   No LLM. No tokens. The downstream meta-agent (M2b) consumes these
   payloads and is the only stage that incurs LLM cost."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0
;; Tuning constants + resource paths

(def ^:private registry-resource-path
  "Classpath location of the M1 label-actions registry. Owned by
   pr-lifecycle; consumed read-only here."
  "config/pr-lifecycle/label-actions.edn")

;------------------------------------------------------------------------------ Layer 0
;; Pure registry helpers

(defn load-registry
  "Read the label-actions registry from the M1 resource. Returns the
   string-keyed map under `:pr-label-actions/registry`.

   Throws an `ex-info` (not an opaque NPE) when the resource is missing
   on the classpath — that's a deploy bug, not a runtime condition,
   and the explicit error message carries the lookup path so an
   operator can see exactly what's missing without reading a stack
   trace."
  []
  (let [resource (io/resource registry-resource-path)]
    (when-not resource
      (throw (ex-info "pr-label-watcher: label-actions registry resource not found on classpath"
                      {:resource-path registry-resource-path
                       :hint "the pr-lifecycle brick owns this resource (M1, PR #906); confirm components/pr-lifecycle/resources is on the classpath"})))
    (-> resource
        slurp
        edn/read-string
        :pr-label-actions/registry)))

(defn matching-labels
  "Set of registry-keys (label strings) that are present on the merged
   PR. Pure intersection — no normalization, GitHub labels are
   case-sensitive at the project boundary."
  [pr-labels registry]
  (set/intersection (set pr-labels) (set (keys registry))))

;------------------------------------------------------------------------------ Layer 0
;; Ancestry predicate

(defn- ancestor-via-shell
  "Default implementation of the ancestor predicate. Calls
   `git merge-base --is-ancestor <base> <merge>` via the supplied
   shell-fn (so tests don't shell out). Exit 0 → ancestor, exit 1 →
   not ancestor, anything else → unknown (treated as not-ancestor to
   match conservatively — better to surface an action than silently
   skip).

   `shell-fn` must accept `[& args]` and return a map with `:exit int`."
  [shell-fn base-sha merge-sha]
  (let [{:keys [exit]} (shell-fn "git" "merge-base" "--is-ancestor"
                                 base-sha merge-sha)]
    (= 0 exit)))

(defn make-ancestor?-fn
  "Build the ancestor predicate `(fn [base-sha merge-sha] -> bool)`.

   `:shell-fn` is REQUIRED and injectable — production passes
   `(partial babashka.process/shell {:out :string :err :string :continue true})`
   or equivalent; tests pass a stub that maps SHA pairs to exit codes.

   Throws an `ex-info` immediately when `:shell-fn` is missing or
   non-fn, so misconfiguration surfaces at predicate-build time
   instead of as an opaque NPE on first call."
  [{:keys [shell-fn] :as opts}]
  (when-not (fn? shell-fn)
    (throw (ex-info "pr-label-watcher: make-ancestor?-fn requires :shell-fn"
                    {:provided (set (keys opts))
                     :hint "pass `:shell-fn (partial babashka.process/shell {:out :string :err :string :continue true})` or an equivalent stub in tests"})))
  (fn [base-sha merge-sha]
    (cond
      (or (nil? base-sha) (nil? merge-sha)) false
      :else (ancestor-via-shell shell-fn base-sha merge-sha))))

(defn workflow-applies?
  "Apply the action's `:applies-when` clause to a candidate workflow.

   Currently the only supported predicate is
   `:workflow.base-sha/not-ancestor-of-merge` — true when the
   workflow's base-sha is NOT an ancestor of the merge-sha (i.e. the
   workflow predates the fix and should pick it up). Workflows with
   no `:workflow/base-sha` (pre-M1 checkpoints) never match; the
   operator handles those manually.

   Open: future predicates (`:workflow/phase-in`, `:workflow/age-gt`)
   can layer on without changing the call site — they ride on the
   same `applies-when` map."
  [workflow action ancestor?-fn merge-sha]
  (let [{:keys [applies-when]} action
        base-sha (:workflow/base-sha workflow)]
    (cond
      (nil? base-sha) false

      (true? (:workflow.base-sha/not-ancestor-of-merge applies-when))
      (not (ancestor?-fn base-sha merge-sha))

      :else false)))

;------------------------------------------------------------------------------ Layer 1
;; Match payload assembly

(defn- workflow-summary
  "Lift the keys that matter for downstream consumers off a workflow
   record. The meta-agent (M2b) and the rebase actuator (M3) need
   `:workflow/id` and `:workflow/base-sha`; everything else lives in
   supervisory-state and can be re-read by those consumers."
  [workflow]
  (select-keys workflow [:workflow/id :workflow/base-sha]))

(defn build-match-payload
  "Build the structured payload that the meta-agent consumes on hit.

   Empty `matched-workflows` means the label matched the registry but
   no active workflows qualify — still emitted, with an empty vector,
   so observability can see \"label fired but every running workflow
   already has the fix\". Operators get the signal without an
   actionable intervention."
  [pr-event label action matched-workflows]
  {:pr/number          (:pr/number pr-event)
   :pr/labels          (set (:pr/labels pr-event))
   :pr/merge-sha       (:pr/merge-sha pr-event)
   :pr-label/label     label
   :pr-label/action    (:action action)
   :pr-label/config    action
   :matched-workflows  (mapv workflow-summary matched-workflows)})

(defn match
  "Pure top-level matcher.

   Inputs:
     - `pr-event` — a `:pr/merged` event map (M1 shape: `:pr/labels`,
        `:pr/merge-sha`, `:pr/number`).
     - `registry` — the loaded `:pr-label-actions/registry` map.
     - `active-workflows` — seq of workflow records (each with
        `:workflow/id` and optionally `:workflow/base-sha`).
     - `ancestor?-fn` — `(fn [base-sha merge-sha] -> bool)` ancestry
        predicate, injectable for tests.

   Output: vector of match payloads, one per matched label. Empty
   when no registry labels match. A label whose registry entry
   matches but whose `:applies-when` excludes every active workflow
   still produces a payload (with empty `:matched-workflows`) so the
   miss is observable."
  [pr-event registry active-workflows ancestor?-fn]
  (let [labels      (matching-labels (:pr/labels pr-event) registry)
        merge-sha   (:pr/merge-sha pr-event)]
    (if (empty? labels)
      []
      (mapv
       (fn [label]
         (let [action  (get registry label)
               matched (filterv
                        #(workflow-applies? % action ancestor?-fn merge-sha)
                        active-workflows)]
           (build-match-payload pr-event label action matched)))
       (sort labels)))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (def reg (load-registry))
  reg
  (def ancestor? (fn [_ _] false)) ; everything is "not ancestor" in this REPL
  (match {:pr/number 123
          :pr/labels #{"dogfood-fix"}
          :pr/merge-sha "abc123"}
         reg
         [{:workflow/id #uuid "11111111-1111-1111-1111-111111111111"
           :workflow/base-sha "old-sha"}]
         ancestor?)
  :leave-this-here)
