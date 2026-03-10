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
  (:require [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Policy checks

(def secret-patterns
  "Patterns that might indicate hardcoded secrets."
  [#"(?i)(password|secret|api[_-]?key|token)\s*=\s*[\"'][^\"']{8,}"
   #"(?i)aws[_-]?(access|secret)[_-]?key[_-]?id?\s*=\s*[\"'][A-Z0-9]{16,}"
   #"sk-[a-zA-Z0-9]{32,}"  ; OpenAI keys
   #"ghp_[a-zA-Z0-9]{36}"]) ; GitHub tokens

(defn check-no-secrets
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

(defn check-plan-complete
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

(defn check-review-approved
  "Check if review is approved."
  [artifact _ctx]
  (let [approved? (or (get-in artifact [:metadata :approved])
                      (get-in artifact [:artifact/metadata :approved])
                      (get-in artifact [:review :approved]))]
    (if approved?
      {:passed? true}
      {:passed? false
       :errors [{:type :not-approved
                 :message "Review not approved"}]})))

(defn check-quality
  "Generic quality check - passes if no explicit failures."
  [artifact _ctx]
  (let [quality-issues (get-in artifact [:metadata :quality-issues] [])]
    (if (empty? quality-issues)
      {:passed? true}
      {:passed? false
       :errors quality-issues})))

(defn check-release-ready
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

;------------------------------------------------------------------------------ Layer 1
;; Registry

(registry/register-gate! :no-secrets)
(registry/register-gate! :plan-complete)
(registry/register-gate! :review-approved)
(registry/register-gate! :quality-check)
(registry/register-gate! :release-ready)

(defmethod registry/get-gate :no-secrets
  [_]
  {:name :no-secrets
   :description "Validates no hardcoded secrets in content"
   :check check-no-secrets
   :repair nil})

(defmethod registry/get-gate :plan-complete
  [_]
  {:name :plan-complete
   :description "Validates plan artifact is complete"
   :check check-plan-complete
   :repair nil})

(defmethod registry/get-gate :review-approved
  [_]
  {:name :review-approved
   :description "Validates review is approved"
   :check check-review-approved
   :repair nil})

(defmethod registry/get-gate :quality-check
  [_]
  {:name :quality-check
   :description "General quality validation"
   :check check-quality
   :repair nil})

(defmethod registry/get-gate :release-ready
  [_]
  {:name :release-ready
   :description "Validates artifact is ready for release"
   :check check-release-ready
   :repair nil})

;------------------------------------------------------------------------------ Layer 2
;; Severity cascade

(defn evaluate-severity-cascade
  "Classify violations by severity into cascade buckets.

   Arguments:
     violations - Vector of violation maps with :violation/severity keys

   Returns:
     {:blocking          [...] ; :critical -> hard-halt
      :approval-required [...] ; :high -> require approval
      :warnings          [...] ; :medium -> warn, passes
      :audits            [...]} ; :low/:info -> audit, passes silently"
  [violations]
  (reduce
   (fn [acc violation]
     (let [severity (:violation/severity violation)]
       (cond
         (= :critical severity) (update acc :blocking conj violation)
         (= :high severity)     (update acc :approval-required conj violation)
         (= :medium severity)   (update acc :warnings conj violation)
         :else                  (update acc :audits conj violation))))
   {:blocking [] :approval-required [] :warnings [] :audits []}
   violations))

(defn request-approval-for-violations!
  "Create approval requests for :high-severity (approval-required) violations.

   Arguments:
     event-stream-atom - Atom used as the approval manager store
     violations        - Vector of approval-required violation maps

   Returns:
     {:approval-ids [...] :pending-count int}"
  [event-stream-atom violations]
  (let [create-fn (requiring-resolve
                    'ai.miniforge.event-stream.approval/create-approval-request)
        store-fn  (requiring-resolve
                    'ai.miniforge.event-stream.approval/store-approval!)
        approvals (mapv (fn [violation]
                          (create-fn
                            (random-uuid)
                            ["policy-reviewer"]
                            1
                            {:metadata {:violation/rule-id    (:violation/rule-id violation)
                                        :violation/message    (:violation/message violation)
                                        :violation/severity   (:violation/severity violation)
                                        :violation/remediation (:violation/remediation violation)}}))
                        violations)]
    (run! #(store-fn event-stream-atom %) approvals)
    {:approval-ids  (mapv :approval/id approvals)
     :pending-count (count approvals)}))

;------------------------------------------------------------------------------ Layer 2
;; Policy Pack Gate (delegates to policy-pack component with severity cascade)

(defn violation->gate-result
  "Convert a violation map to a gate result entry."
  [result-type violation]
  (cond-> {:type result-type
           :severity (:violation/severity violation)
           :message (:violation/message violation)}
    (:violation/rule-id violation) (assoc :rule-id (:violation/rule-id violation))
    (:violation/remediation violation) (assoc :remediation (:violation/remediation violation))))

(defn check-policy-pack
  "Check artifact against loaded policy packs with severity cascade.

   Severity cascade:
   - :critical -> hard-halt (blocks gate, errors)
   - :high     -> require-approval (blocks unless approved)
   - :medium   -> warn (passes with warnings)
   - :low/:info -> audit (passes silently, recorded in evidence)

   Arguments:
     artifact - Artifact with :content
     ctx      - Execution context with :policy-packs, :task-type, :phase

   Returns:
     {:passed? bool :errors [] :warnings [] :approval-required []}"
  [artifact ctx]
  (try
    (let [check-fn (requiring-resolve 'ai.miniforge.policy-pack.core/check-artifact)
          packs (get ctx :policy-packs [])
          task-type (get ctx :task-type :implement)
          phase (get ctx :phase :implement)]
      (if (empty? packs)
        {:passed? true :warnings [{:type :no-policy-packs
                                    :message "No policy packs loaded"}]}
        (let [result   (check-fn packs artifact {:task-type task-type :phase phase})
              cascade  (evaluate-severity-cascade (:violations result []))
              blocking          (:blocking cascade)
              approval-required (:approval-required cascade)
              warnings          (:warnings cascade)]
          {:passed? (and (empty? blocking) (empty? approval-required))
           :errors (mapv #(violation->gate-result :policy-violation %) blocking)
           :approval-required (mapv #(violation->gate-result :approval-required %) approval-required)
           :warnings (mapv #(violation->gate-result :policy-warning %)
                           (concat warnings (:audits cascade)))})))
    (catch Exception e
      {:passed? true
       :warnings [{:type :policy-check-error
                    :message (str "Policy check failed: " (ex-message e))}]})))

(defn repair-policy-pack
  "Attempt to repair policy violations.
   Currently returns failure — repair requires LLM agent."
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors errors
   :message "Policy violation repair requires LLM agent"})

(registry/register-gate! :policy-pack)

(defmethod registry/get-gate :policy-pack
  [_]
  {:name :policy-pack
   :description "Validates code against loaded policy packs with severity cascade"
   :check check-policy-pack
   :repair repair-policy-pack})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-no-secrets {:content "(def password \"secret123\")"} {})
  (check-no-secrets {:content "(def x 42)"} {})
  (check-plan-complete {:plan {:steps [{:name "step1"}]}} {})
  :leave-this-here)
