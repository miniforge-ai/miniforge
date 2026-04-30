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
            [ai.miniforge.gate.registry :as registry]
            [ai.miniforge.response.interface :as response]))

;;------------------------------------------------------------------------------ Layer 1
;; Gate implementation

(def ^:private no-policy-packs-warning
  {:type :no-policy-packs
   :message "No policy packs loaded — behavioral check skipped"})

(def ^:private behavioral-check-error-prefix
  "Behavioral check failed, skipping: ")

(def ^:private repair-required-message
  "Behavioral violations require redirect to :implement phase — no in-place repair")

(defn- passed-cascade?
  [{:keys [blocking approval-required]}]
  (and (empty? blocking)
       (empty? approval-required)))

(defn- violation-results
  [result-type violations]
  (mapv #(policy/violation->gate-result result-type %)
        violations))

(defn- behavioral-check-error-warning
  [message]
  {:type :behavioral-check-error
   :message (str behavioral-check-error-prefix message)})

(defn- repair-required-result
  [artifact errors]
  (assoc (response/failure repair-required-message)
         :artifact artifact
         :errors errors
         :message repair-required-message))

(defn- soft-dep-resolve-fn
  "Build a policy-pack-shaped check fn that lazily resolves `sym` at
   call time.

   `sym` is a fully-qualified Clojure symbol identifying a 3-arg
   `(check-artifact packs artifact opts)` function in a *soft*
   dependency — i.e. a namespace that may not be on the classpath
   (gate's `deps.edn` deliberately omits policy-pack).

   On call, the returned fn:
     - tries `(requiring-resolve sym)`, wrapping any thrown
       Exception (FileNotFoundException, ClassNotFoundException, etc.)
       in a consistent `ex-info` whose message ends with `unavailable`
       and whose `ex-data` carries `:sym` plus the original cause type;
     - if `requiring-resolve` returns `nil` (rare, but possible if the
       ns loads but the var is missing), throws the same ex-info shape;
     - otherwise calls the resolved fn with the supplied args.

   `check-behavioral`'s outer `try/catch` converts any throw here into
   a `:behavioral-check-error` warning."
  [sym]
  (fn [packs artifact opts]
    (let [resolved (try
                     (requiring-resolve sym)
                     (catch Exception e
                       (throw (ex-info (str sym " unavailable")
                                       {:sym sym
                                        :cause-type (some-> e class .getName)}
                                       e))))]
      (when-not resolved
        (throw (ex-info (str sym " unavailable")
                        {:sym sym})))
      (resolved packs artifact opts))))

(def ^:private default-check-fn
  "Production default for `:check-fn`. Lazily resolves
   `ai.miniforge.policy-pack.core/check-artifact` on each invocation
   so gate works with policy-pack on the classpath and degrades to a
   `:behavioral-check-error` warning otherwise."
  (soft-dep-resolve-fn 'ai.miniforge.policy-pack.core/check-artifact))

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
                                   {:violations [...]}.

   Returns:
     {:passed? bool
      :errors  [{:type :behavioral-violation :severity … :message … …}]
      :warnings [{:type :behavioral-warning :severity … :message … …}]}"
  [artifact ctx]
  ;; Programmer-error guard runs OUTSIDE the try/catch on purpose.
  ;; A non-nil non-callable :check-fn is a misconfiguration the caller
  ;; needs to see, not a runtime failure to be silently demoted to a
  ;; :behavioral-check-error warning. The outer catch below covers
  ;; runtime failures only (soft-dep unavailable, policy-pack throws).
  (let [provided (:check-fn ctx)]
    (when (and (some? provided) (not (ifn? provided)))
      (throw (IllegalArgumentException.
              (str ":check-fn must be a function, got: "
                   (class provided))))))
  (try
    (let [provided (:check-fn ctx)
          ;; nil (missing OR explicitly nil) → use default;
          ;; ifn? guaranteed by the guard above when non-nil.
          check-fn (if (nil? provided) default-check-fn provided)
          packs    (get ctx :policy-packs [])]
      (if (empty? packs)
        {:passed?  true
         :warnings [no-policy-packs-warning]}
        (let [result   (check-fn packs artifact {:phase :observe :task-type :modify})
              cascade  (policy/evaluate-severity-cascade (get result :violations []))
              blocking (get cascade :blocking [])
              approval-required (get cascade :approval-required [])
              warnings (get cascade :warnings [])
              audits (get cascade :audits [])]
          {:passed?  (passed-cascade? cascade)
           :errors   (violation-results :behavioral-violation
                                        (concat blocking approval-required))
           :warnings (violation-results :behavioral-warning
                                        (concat warnings audits))})))
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
