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
                         context {:phase :observe :task-type :modify}, which
                         classifies violations by each rule's enforcement action
                         (:hard-halt / :require-approval block; :warn / :audit
                         warn) — not by severity.
   - repair-behavioral : always returns {:success? false} — behavioral
                         violations cannot be fixed in-place; the caller
                         must redirect to :implement."
  (:require [ai.miniforge.gate.messages :as msg]
            [ai.miniforge.gate.registry :as registry]
            [ai.miniforge.policy-pack.interface :as policy-pack]
            [ai.miniforge.response.interface :as response]))

;;------------------------------------------------------------------------------ Layer 1
;; Gate implementation

(defn- no-policy-packs-warning
  []
  {:type :no-policy-packs
   :message (msg/t :behavioral/no-policy-packs)})

(defn- behavioral-check-error-warning
  [message]
  {:type :behavioral-check-error
   :message (str (msg/t :behavioral/check-error-prefix) message)})

(defn- repair-required-result
  [artifact errors]
  (let [m (msg/t :behavioral/repair-required)]
    (assoc (response/failure m)
           :artifact artifact
           :errors errors
           :message m)))

(def ^:private default-check-fn
  "Production default for `:check-fn`."
  #'policy-pack/check-artifact)

(defn check-behavioral
  "Check a telemetry artifact against loaded policy packs for behavioral violations.

   Arguments:
     artifact - Map with :event-stream/events (vector of telemetry event maps)
     ctx      - Execution context with:
                  :policy-packs  - Vector of loaded policy-pack maps
                  :phase         - Expected :observe (used for logging; gate
                                   always runs with {:phase :observe})
                  :check-fn      - Optional override for the policy-pack
                                   check function.  Tests inject a stub here
                                   instead of redefining clojure.core fns
                                   globally (which races with parallel tests).
                                   Signature: (fn [packs artifact opts]) ->
                                   the check-artifact result
                                   {:passed? :blocking :require-approval
                                    :warnings :audits}.

   Returns:
     {:passed? bool
      :errors  [{:code … :severity … :message … …}]
      :warnings [{:code … :severity … :message … …}]}"
  [artifact ctx]
  ;; Programmer-error guard runs OUTSIDE the try/catch on purpose.
  ;; A non-nil non-callable :check-fn is a misconfiguration the caller
  ;; needs to see, not a runtime failure to be silently demoted to a
  ;; :behavioral-check-error warning. The outer catch below covers
  ;; runtime failures only (soft-dep unavailable, policy-pack throws).
  (let [provided (:check-fn ctx)]
    (when (and (some? provided) (not (ifn? provided)))
      (throw (IllegalArgumentException.
              (msg/t :behavioral/check-fn-not-function
                     {:class (class provided)})))))
  (try
    (let [provided (:check-fn ctx)
          ;; nil (missing OR explicitly nil) → use default;
          ;; ifn? guaranteed by the guard above when non-nil.
          check-fn (if (nil? provided) default-check-fn provided)
          packs    (get ctx :policy-packs [])]
      (if (empty? packs)
        {:passed?  true
         :warnings [(no-policy-packs-warning)]}
        (let [{:keys [passed? blocking require-approval warnings audits]}
              (check-fn packs artifact {:phase :observe :task-type :modify})]
          {:passed?  (and passed? (empty? require-approval))
           :errors   (vec (concat blocking require-approval))
           :warnings (vec (concat warnings audits))})))
    (catch Exception e
      {:passed?  true
       :warnings [(behavioral-check-error-warning (ex-message e))]})))

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
  (repair-required-result artifact errors))

;;------------------------------------------------------------------------------ Layer 1
;; Registry

(registry/register-gate! :behavioral)

(defmethod registry/get-gate :behavioral
  [_]
  {:name        :behavioral
   :description (msg/t :behavioral/description)
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
