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

(ns ai.miniforge.tui-views.effect
  "Side-effect constructors for the Elm runtime.

   Every side-effect is a map with :type as the dispatch key. This namespace
   provides named constructors so the format is defined in one place and
   callers read as a DSL.

   Layer 0 — no dependencies on other tui-views namespaces.")

;------------------------------------------------------------------------------ Layer 0
;; Side-effect constructors

(defn sync-prs
  "Sync PRs from all fleet repos. Optionally filter by state."
  ([] {:type :sync-prs})
  ([state] {:type :sync-prs :state state}))

(defn discover-repos
  "Discover repositories from a GitHub org/user and add to fleet."
  [owner]
  {:type :discover-repos :owner owner})

(defn browse-repos
  "Browse remote repositories (read-only, no config change).
   opts may include :owner, :provider, :source, :limit."
  [opts]
  (merge {:type :browse-repos} opts))

(defn open-url
  "Open a URL in the system browser."
  [url]
  {:type :open-url :url url})

(defn evaluate-policy
  "Evaluate policy packs against a PR."
  [pr-id pr]
  {:type :evaluate-policy :pr-id pr-id :pr pr})

(defn batch-evaluate-policy
  "Evaluate policy for multiple PRs that don't have evaluation results."
  [prs]
  {:type :batch-evaluate-policy :prs prs})

(defn create-train
  "Create a new merge train with the given name."
  [train-name]
  {:type :create-train :name train-name})

(defn add-to-train
  "Add PRs to an existing train."
  [train-id prs]
  {:type :add-to-train :train-id train-id :prs prs})

(defn merge-next
  "Merge the next ready PR in a train."
  [train-id]
  {:type :merge-next :train-id train-id})

(defn review-prs
  "Run policy review on a set of PRs."
  [prs]
  {:type :review-prs :prs prs})

(defn remediate-prs
  "Attempt auto-remediation of policy violations on PRs."
  [prs]
  {:type :remediate-prs :prs prs})

(defn decompose-pr
  "Analyze a PR for decomposition into sub-PRs."
  [pr]
  {:type :decompose-pr :pr pr})

(defn control-action
  "Create a control action effect to pause/resume/cancel a workflow.
   The effect handler writes a command file to ~/.miniforge/commands/<workflow-id>/."
  [action-type workflow-id]
  {:type :control-action
   :action action-type
   :workflow-id workflow-id})

(defn chat-send
  "Send a chat message to the orchestrator."
  [context message history]
  {:type :chat-send :context context :message message :history history})

(defn fleet-risk-triage
  "Request LLM risk triage across all fleet PRs.
   pr-summaries is a vector of {:id [repo num] :summary str} maps."
  [pr-summaries]
  {:type :fleet-risk-triage :pr-summaries pr-summaries})

(defn archive-workflows
  "Persistently archive workflows by moving their event files to archive/."
  [workflow-ids]
  {:type :archive-workflows :workflow-ids workflow-ids})

(defn cache-policy-result
  "Persist a single PR's policy evaluation result to the disk cache."
  [pr-id result prs]
  {:type :cache-policy-result :pr-id pr-id :result result :prs prs})

(defn cache-risk-triage
  "Persist fleet risk triage results to the disk cache."
  [risk-map prs]
  {:type :cache-risk-triage :risk-map risk-map :prs prs})
