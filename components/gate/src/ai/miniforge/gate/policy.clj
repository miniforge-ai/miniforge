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
(ns ai.miniforge.gate.policy
  "Policy validation gates.

   - :no-secrets - Check for hardcoded secrets
   - :quality-check - General quality validation
   - :review-approved - Review approval check
   - :release-ready - Release readiness check
   - :plan-complete - Plan completeness check"
  (:require [ai.miniforge.gate.messages  :as msg]
            [ai.miniforge.gate.registry  :as registry]
            [ai.miniforge.policy-pack.interface :as policy-pack]
            [ai.miniforge.response.interface :as response]
            [slingshot.slingshot         :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;; Policy checks
(def ^{:stratum 0} secret-patterns
  "Patterns that might indicate hardcoded secrets."
  [#"(?i)(password|secret|api[_-]?key|token)\s*=\s*[\"'][^\"']{8,}"
   #"(?i)aws[_-]?(access|secret)[_-]?key[_-]?id?\s*=\s*[\"'][A-Z0-9]{16,}"
   #"sk-[a-zA-Z0-9]{32,}"  ; OpenAI keys
   #"ghp_[a-zA-Z0-9]{36}"])  ; GitHub tokens

(defn ^{:stratum 0} check-plan-complete
  "Check if plan artifact is complete."
  [artifact _ctx]
  (let [plan (or (:plan artifact)
                 (get-in artifact [:artifact/content :plan])
                 artifact)
        steps (or (:steps plan)
                  (:plan/tasks plan)
                  (:tasks plan))]
    (cond
      ;; Already-satisfied plans pass without tasks
      (= :already-satisfied (:plan/status plan))
      {:passed? true
       :step-count 0}

      (nil? plan)
      {:passed? false
       :errors [{:type :no-plan
                 :message "No plan found in artifact"}]}

      (empty? steps)
      {:passed? false
       :errors [{:type :empty-plan
                 :message "Plan has no steps"}]}

      :else
      {:passed? true
       :step-count (count steps)})))

(defn ^{:stratum 0} check-review-approved
  "Check if review is approved.

   Reads the decision from the ONE canonical location
   (`response/review-decision` → `:review/decision` on the phase output the
   gate is handed). No fossil fallbacks (`[:metadata :approved]`,
   `[:artifact/metadata :approved]`, `[:review :approved]`): if the decision
   isn't where the contract says, the review is not approved — loudly —
   rather than silently coerced from a legacy boolean flag. The gate runner
   passes the phase `:output` (the verdict) as the artifact."
  [artifact _ctx]
  (let [decision (response/review-decision artifact)
        approved? (contains? #{:approved :conditionally-approved} decision)]
    (if approved?
      {:passed? true}
      {:passed? false
       :errors [{:type :not-approved
                 :message (msg/t :review/not-approved {:decision (pr-str decision)})}]})))

(defn ^{:stratum 0} check-quality
  "Generic quality check - passes if no explicit failures."
  [artifact _ctx]
  (let [quality-issues (get-in artifact [:metadata :quality-issues] [])]
    (if (empty? quality-issues)
      {:passed? true}
      {:passed? false
       :errors quality-issues})))

(defn ^{:stratum 0} check-release-ready
  "Check release readiness."
  [artifact _ctx]
  (let [ready? (or (get-in artifact [:metadata :release-ready])
                   (get-in artifact [:release :ready])
                   true)]  ; Default to ready
    (if ready?
      {:passed? true}
      {:passed? false
       :errors [{:type :not-release-ready
                 :message "Artifact not ready for release"}]})))

;; Policy Pack Gate (classifies by enforcement action, via policy-pack)
(defn ^{:stratum 0} check-policy-pack
  "Check artifact against loaded policy packs, classifying each violation by its
   rule's enforcement action (`:rule/enforcement :action`) via
   `policy-pack/check-artifact`. `:hard-halt` and `:require-approval` block the
   gate; `:warn` and `:audit` are non-blocking warnings. Severity is advisory
   and never gates.

   Fail-closed: any exception during evaluation blocks, AND an empty pack set
   blocks. This gate guards the deploy path, where zero policy is a
   misconfiguration, not an allowance — the caller (e.g. the provision phase)
   supplies the deployment policy pack via ctx `:policy-packs`. (The
   phase-scoped SDLC gate keeps the softer skip-on-no-packs posture, since a
   repo legitimately without a standards pack is allowed there.)

   Arguments:
     artifact - Artifact with :artifact/content (and optional :artifact/path),
                forwarded to policy-pack/check-artifact
     ctx      - Execution context with :policy-packs, :task-type, :phase

   Returns:
     {:passed? bool :errors [] :warnings [] :approval-required []}. Blocking
     require-approval violations appear in BOTH :errors (so the failure carries
     detail — `gate.interface/check-gate` emits failure events from :errors
     only) and :approval-required (the subset needing approval)."
  [artifact ctx]
  (try+
    (let [packs (get ctx :policy-packs [])
          task-type (get ctx :task-type :implement)
          phase (get ctx :phase :implement)]
      (if (empty? packs)
        {:passed? false
         :errors [{:type :no-policy-packs
                   :message (msg/t :policy-pack/no-policy-packs-blocked)}]
         :approval-required []
         :warnings []}
        (let [{:keys [passed? blocking require-approval warnings audits]}
              (policy-pack/check-artifact packs artifact {:task-type task-type :phase phase})]
          {:passed?           (and passed? (empty? require-approval))
           :errors            (vec (concat blocking require-approval))
           :approval-required (vec require-approval)
           :warnings          (vec (concat warnings audits))})))
    (catch Object e
      {:passed? false
       :errors [{:type :policy-check-error
                  :message (msg/t :policy-pack/check-error {:message (ex-message e)})}]
       :warnings []
       :approval-required []})))

(defn ^{:stratum 0} repair-policy-pack
  "Attempt to repair policy violations.
   Currently returns failure — repair requires LLM agent."
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors errors
   :message "Policy violation repair requires LLM agent"})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check-no-secrets
  "Check for hardcoded secrets in content."
  [artifact _ctx]
  (let [content (or (:content artifact)
                    (get-in artifact [:artifact/content])
                    "")
        content-str (if (string? content) content (pr-str content))
        matches (for [pattern secret-patterns
                      :let [matches (re-seq pattern content-str)]
                      :when (seq matches)]
                  {:pattern (str pattern)
                   :count (count matches)})]
    (if (empty? matches)
      {:passed? true}
      {:passed? false
       :errors [{:type :secrets-detected
                 :message "Potential hardcoded secrets detected"
                 :matches matches}]})))

(defmethod ^{:stratum 1} registry/get-gate :no-secrets
  [_]
  {:name :no-secrets
   :description "Validates no hardcoded secrets in content"
   :check check-no-secrets
   :repair nil})

(defmethod ^{:stratum 1} registry/get-gate :plan-complete
  [_]
  {:name :plan-complete
   :description "Validates plan artifact is complete"
   :check check-plan-complete
   :repair nil})

(defmethod ^{:stratum 1} registry/get-gate :review-approved
  [_]
  {:name :review-approved
   :description "Validates review is approved"
   :check check-review-approved
   :repair nil})

(defmethod ^{:stratum 1} registry/get-gate :quality-check
  [_]
  {:name :quality-check
   :description "General quality validation"
   :check check-quality
   :repair nil})

(defmethod ^{:stratum 1} registry/get-gate :release-ready
  [_]
  {:name :release-ready
   :description "Validates artifact is ready for release"
   :check check-release-ready
   :repair nil})

(defmethod ^{:stratum 1} registry/get-gate :policy-pack
  [_]
  {:name :policy-pack
   :description "Validates code against loaded policy packs by enforcement action"
   :check check-policy-pack
   :repair repair-policy-pack})

;; Registry
(registry/register-gate! :no-secrets)

(registry/register-gate! :plan-complete)

(registry/register-gate! :review-approved)

(registry/register-gate! :quality-check)

(registry/register-gate! :release-ready)

(registry/register-gate! :policy-pack)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-no-secrets {:content "(def password \"secret123\")"} {})
  (check-no-secrets {:content "(def x 42)"} {})
  (check-plan-complete {:plan {:steps [{:name "step1"}]}} {})
  :leave-this-here)
