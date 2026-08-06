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
(ns ai.miniforge.cli.workflow-runner.setup
  "Run setup vocabulary: workflow interface resolution, load +
   validation (with inline fallbacks for :test-only / :comment-fix),
   workflow-type selection (explicit or LLM-recommended), alias
   resolution, phase callbacks, and governed workflow-id minting.
   Split out of `ai.miniforge.cli.workflow-runner` (rule 210: the
   parent namespace measured 10 real layers, max 3; each concern moves
   to its own layer-coherent file)."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-recommender :as recommender]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.interface :as workflow]
   [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

;; Workflow interface resolution and pipeline helpers
(defn ^{:stratum 0} resolve-workflow-interface []
  {:load-workflow workflow/load-workflow
   :run-pipeline  workflow/run-pipeline})

(defn ^{:stratum 0} create-phase-callbacks [_quiet]
  ;; Phase progress is handled by the event-stream subscription
  ;; (display/start-progress!). Callbacks retained as extension point.
  {})

(defn ^{:stratum 0} load-and-validate-workflow [load-workflow workflow-id version]
  (let [{:keys [workflow validation]} (load-workflow workflow-id version {})]
    (when-not workflow
      (response/throw-anomaly! :anomalies/not-found
                               (messages/t :workflow-runner/not-found {:workflow-id workflow-id})
                               {:workflow-id workflow-id :version version}))
    (when (and validation (not (:valid? validation)))
      (response/throw-anomaly! :anomalies.workflow/invalid-config
                               (messages/t :workflow-runner/validation-failed {:errors (:errors validation)})
                               {:workflow-id workflow-id :validation validation}))
    workflow))

(defn ^{:stratum 0} select-workflow-type
  "Select workflow type using LLM recommendation if not explicitly specified.
   Checks :spec/workflow-type first, then :workflow/type as a fallback for
   specs that use the shorter key."
  [spec llm-client quiet]
  (if-let [explicit-type (or (:spec/workflow-type spec)
                             (:workflow/type spec))]
    (do
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/user-specified {:workflow-type (name explicit-type)}))))
      explicit-type)
    (let [recommendation (recommender/recommend-workflow-with-fallback spec llm-client)]
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/auto-selected {:workflow-type (name (:workflow recommendation))})))
        (println (messages/t :workflow-runner/auto-selected-reason {:reasoning (:reasoning recommendation)}))
        (when (= :llm (:source recommendation))
          (println (messages/t :workflow-runner/auto-selected-confidence {:confidence (format "%.0f%%" (* 100 (:confidence recommendation 0.0)))})))
        (println (display/colorize :yellow (messages/t :workflow-runner/auto-selected-override))))
      (:workflow recommendation))))

(def ^{:stratum 0} ^:private workflow-aliases
  "Map legacy/alternate workflow type keywords to their canonical counterparts.
   Many work specs use :standard-sdlc but the only registered workflow is
   :canonical-sdlc."
  {:standard-sdlc :canonical-sdlc})

;; Spec-driven execution
(defn ^{:stratum 0} governed-workflow-id
  "A UUID workflow id for a governed run. The operator control channel
   routes `:pause`/`:resume`/`:cancel` interventions by coercing the
   target id to a UUID, so a run WITHOUT a UUID id is uncontrollable —
   its interventions can't reach its live-runner registry or audit
   trail. Use the spec's `:session-id` when it already is a UUID (or a
   UUID string); otherwise mint one. A PRESENT-but-non-UUID session-id
   is warned about (it was silently discarded); an absent one is the
   normal case and mints quietly."
  [session-id quiet]
  (or (when (uuid? session-id) session-id)
      (when (string? session-id) (parse-uuid session-id))
      (let [fresh (random-uuid)]
        (when (and (some? session-id) (not quiet))
          (println (display/colorize
                    :yellow
                    (messages/t :workflow-runner/non-uuid-session-id
                                {:session-id (pr-str session-id)
                                 :workflow-id (str fresh)}))))
        fresh)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-workflow-alias
  "Resolve a workflow type through the alias map. Returns the canonical type
   if an alias exists, otherwise returns the type unchanged."
  [workflow-type]
  (get workflow-aliases workflow-type workflow-type))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-or-create-workflow [load-workflow workflow-type workflow-version]
  (let [workflow-type (resolve-workflow-alias workflow-type)]
    (try+
      (load-and-validate-workflow load-workflow workflow-type workflow-version)
      (catch [:anomaly/category :anomalies/not-found] _
        (case workflow-type
          :test-only
          {:workflow/id :test-only
           :workflow/version "inline"
           :workflow/name "Test Generation"
           :workflow/pipeline [{:phase :verify} {:phase :done}]
           :workflow/config {:max-tokens 20000 :max-iterations 10}}

          :comment-fix
          {:workflow/id :comment-fix
           :workflow/version "inline"
           :workflow/name "Comment Fix"
           :workflow/pipeline [{:phase :implement :gates [:syntax :lint :no-secrets]}
                               {:phase :done}]
           :workflow/config {:max-tokens 20000 :max-iterations 5}}

          (throw+))))))
