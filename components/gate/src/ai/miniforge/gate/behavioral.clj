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

(ns ai.miniforge.gate.behavioral
  "Behavioral gate — validates telemetry event streams against policy packs.

   Checks harness execution telemetry collected during :observe phase for
   behavioral policy violations.  The artifact is expected to carry
   :event-stream/events (a vector of telemetry event maps produced by the
   harness).

   - check-behavioral  : delegates to policy-pack/check-artifact with phase
                         context {:phase :observe :task-type :modify} and
                         uses the severity cascade from gate.policy.
   - repair-behavioral : always returns {:success? false} — behavioral
                         violations cannot be fixed in-place; the caller
                         must redirect to :implement."
  (:require [ai.miniforge.gate.policy   :as policy]
            [ai.miniforge.gate.registry :as registry]))

;;------------------------------------------------------------------------------ Layer 1
;; Gate implementation

(defn check-behavioral
  "Check a telemetry artifact against loaded policy packs for behavioral violations.

   Arguments:
     artifact - Map with :event-stream/events (vector of telemetry event maps)
     ctx      - Execution context with:
                  :policy-packs  - Vector of loaded policy-pack maps
                  :phase         - Expected :observe (used for logging; gate
                                   always runs with {:phase :observe})

   Returns:
     {:passed? bool
      :errors  [{:type :behavioral-violation :severity … :message … …}]
      :warnings [{:type :behavioral-warning :severity … :message … …}]}"
  [artifact ctx]
  (try
    (let [check-fn (requiring-resolve 'ai.miniforge.policy-pack.core/check-artifact)
          packs    (get ctx :policy-packs [])]
      (if (empty? packs)
        {:passed?  true
         :warnings [{:type    :no-policy-packs
                     :message "No policy packs loaded — behavioral check skipped"}]}
        (let [result   (check-fn packs artifact {:phase :observe :task-type :modify})
              cascade  (policy/evaluate-severity-cascade (:violations result []))
              blocking          (:blocking cascade)
              approval-required (:approval-required cascade)
              warnings          (:warnings cascade)
              audits            (:audits cascade)]
          {:passed?  (and (empty? blocking) (empty? approval-required))
           :errors   (mapv #(policy/violation->gate-result :behavioral-violation %)
                           (concat blocking approval-required))
           :warnings (mapv #(policy/violation->gate-result :behavioral-warning %)
                           (concat warnings audits))})))
    (catch Exception e
      {:passed?  true
       :warnings [{:type    :behavioral-check-error
                   :message (str "Behavioral check failed, skipping: " (ex-message e))}]})))

(defn repair-behavioral
  "Behavioral violations cannot be fixed in-place.

   The caller must redirect the workflow to the :implement phase for an LLM
   agent to address the root cause.

   Returns:
     {:success? false
      :artifact artifact
      :errors   errors
      :message  string}"
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors   errors
   :message  "Behavioral violations require redirect to :implement phase — no in-place repair"})

;;------------------------------------------------------------------------------ Layer 1
;; Registry

(registry/register-gate! :behavioral)

(defmethod registry/get-gate :behavioral
  [_]
  {:name        :behavioral
   :description "Validates harness telemetry event stream against policy packs (phase: observe)"
   :check       check-behavioral
   :repair      repair-behavioral})

;;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Smoke test — no packs loaded
  (check-behavioral
   {:event-stream/events [{:event/type :tool-call :tool "Write"}]}
   {:policy-packs [] :phase :observe})
  ;; => {:passed? true :warnings [{:type :no-policy-packs ...}]}

  ;; Repair is always a no-op
  (repair-behavioral {:event-stream/events []} [] {:phase :observe})
  ;; => {:success? false :artifact ... :errors [] :message "..."}

  :leave-this-here)
