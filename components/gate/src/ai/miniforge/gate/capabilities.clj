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
(ns ai.miniforge.gate.capabilities
  "Inject the mechanical tool gates into the policy-pack capability registry.

   policy-pack is depended ON by `gate` (transitively via connector-linter ->
   compliance-scanner -> policy-pack, and now directly), so policy-pack cannot
   depend on the tool gates without a cycle. This namespace lives in the gate
   layer — which already depends on both policy-pack and the gate
   implementations — and INJECTS each mechanical check into the registry by
   calling `policy-pack/register-capability!`. No requiring-resolve is used:
   the gate checks are reached through ordinary requires.

   Capabilities registered:
   - :lint       -> gate :lint       (clj-kondo lint gate)
   - :format     -> pure format-drift check requiring explicit formatted
                    content or an injected check function
   - :syntax     -> gate :syntax     (reader/syntax gate)
   - :no-secrets -> gate :no-secrets (policy no-secrets gate)

   Each injected check-fn runs the gate's `:check` (via the gate registry),
   adapting its `{:passed? :errors}` (or `response/success`) result into the
   policy-pack violation shape (N4 §3.1), or nil on pass. It requires the
   gate registry + the gate implementations directly (NOT gate.interface),
   to avoid a namespace require cycle, since gate.interface loads this ns.

   Registration is idempotent (`register-capability!` replaces by key) and
   safe to call repeatedly."
  (:require
   ;; Require implementations for side effects (gate registration), so the
   ;; gates are available when this ns runs them — independent of load order.
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.format]
   [ai.miniforge.gate.policy]
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

;; Tool-gate result adaptation
(defn ^{:stratum 0} gate-failed?
  "True when a gate result indicates failure. Mirrors
   `gate.interface/passed?`: accepts the legacy `:passed?` shape and the
   canonical `response/success` / `response/error` shapes."
  [result]
  (cond
    (contains? result :passed?) (not (boolean (:passed? result)))
    (response/success? result)  false
    (response/error? result)    true
    :else                       true))

(defn- ^{:stratum 0} artifact-content
  [artifact]
  (cond
    (contains? artifact :artifact/content) (get artifact :artifact/content)
    (contains? artifact :content)          (get artifact :content)
    :else                                  ""))

(defn- ^{:stratum 0} expected-formatted-content
  [artifact]
  (or (get artifact :artifact/formatted-content)
      (get artifact :formatted-content)))

(defn- ^{:stratum 0} format-drift-violation
  [artifact message]
  {:type          :capability
   :capability    :format
   :artifact-path (or (:artifact/path artifact) (:path artifact))
   :message       message})

(defn- ^{:stratum 0} injected-format-result
  [artifact context]
  (when-let [check-fn (:format-check-fn context)]
    (check-fn artifact context)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} gate-result->violation
  "Adapt a gate result into the policy-pack capability violation shape, or
   nil when the gate passed. `capability` keys the violation; `artifact`
   supplies the path. Preserves diagnostics from BOTH the legacy
   `{:errors [...]}` shape and the canonical `response/error` shape (whose
   detail lives under `:error`), so response-based gates don't lose their
   message/data."
  [capability gate-result artifact]
  (when (gate-failed? gate-result)
    (let [errors (vec (or (seq (:errors gate-result))
                          (when (response/error? gate-result)
                            [{:message (get-in gate-result [:error :message])
                              :data    (get-in gate-result [:error :data])}])))]
      {:type          :capability
       :capability    capability
       :matches       errors
       :artifact-path (or (:artifact/path artifact) (:path artifact))
       :message       (str (name capability) " capability failed: "
                            (or (some-> errors first :message)
                                "tool reported a violation"))})))

(defn- ^{:stratum 1} artifact-format-result
  [artifact]
  (when-let [formatted (expected-formatted-content artifact)]
    {:passed? (= formatted (artifact-content artifact))
     :errors  [{:message "Artifact content is not formatted"}]}))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} run-gate-capability
  "Run the named `gate-kw` gate's `:check` on `artifact`/`context` and adapt
   the result into a capability violation (or nil on pass). Tagged
   `capability` for the violation shape.

   A gate check that throws is converted into a failing gate-result here (the
   raw `:check` lacks the try/catch that `gate.interface/check-gate` provides),
   so a tool/IO/misconfiguration error surfaces as a fail-loud violation —
   data, not an exception escaping rule evaluation."
  [capability gate-kw artifact context]
  (let [{:keys [check]} (registry/get-gate gate-kw)
        result          (try
                          (check artifact context)
                          (catch Exception e
                            {:passed? false
                             :errors  [{:message (str (name gate-kw)
                                                      " gate check threw: "
                                                      (ex-message e))}]}))]
    (gate-result->violation capability result artifact)))

(defn ^{:stratum 2} check-format
  "Format capability — performs a pure formatting check.

   The existing :format gate repairs via LSP and intentionally reports pass for
   check-only mode. Policy enforcement needs a check that cannot silently pass,
   so this capability accepts either an injected pure `:format-check-fn` in the
   context or an artifact-provided `:artifact/formatted-content` expectation.
   Without either, the capability returns a violation naming the missing check
   contract."
  [artifact context]
  (let [result (or (injected-format-result artifact context)
                   (artifact-format-result artifact))]
    (if result
      (gate-result->violation :format result artifact)
      (format-drift-violation artifact
                              "Format capability requires :format-check-fn or :artifact/formatted-content"))))

;------------------------------------------------------------------------------ Layer 3

;; Built-in mechanical capability checks (each: (fn [artifact context] -> violation-or-nil))
(defn ^{:stratum 3} check-lint
  "Lint capability — wraps the clj-kondo lint gate."
  [artifact context]
  (run-gate-capability :lint :lint artifact context))

(defn ^{:stratum 3} check-syntax
  "Syntax capability — wraps the reader/syntax gate."
  [artifact context]
  (run-gate-capability :syntax :syntax artifact context))

(defn ^{:stratum 3} check-no-secrets
  "No-secrets capability — wraps the policy no-secrets gate."
  [artifact context]
  (run-gate-capability :no-secrets :no-secrets artifact context))

;------------------------------------------------------------------------------ Layer 4

;; Injection
(def ^{:stratum 4} ^:private mechanical-capabilities
  "Capability keyword -> {:meta {...} :check fn} for the mechanical tool
   gates. Injected into the policy-pack registry by
   `register-mechanical-capabilities!`."
  {:lint       {:meta  {:tool :clj-kondo
                        :class :deterministic
                        :description "Lint Clojure code via the clj-kondo gate"}
                :check check-lint}
   :format     {:meta  {:tool :formatter
                        :class :deterministic
                        :description "Detect formatting drift through a pure format check"}
                :check check-format}
   :syntax     {:meta  {:tool :reader
                        :class :deterministic
                        :description "Parse code via the reader/syntax gate"}
                :check check-syntax}
   :no-secrets {:meta  {:tool :policy-gate
                        :class :deterministic
                        :description "Detect hardcoded secrets via the policy gate"}
                :check check-no-secrets}})

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} register-mechanical-capabilities!
  "Inject the mechanical tool gates into the policy-pack capability registry.

   Idempotent: each call (re)registers :lint, :format, :syntax and
   :no-secrets. Returns the set of capability keywords now available."
  []
  (doseq [[capability-kw entry] mechanical-capabilities]
    (policy-pack/register-capability! capability-kw entry))
  (policy-pack/list-capabilities))

;------------------------------------------------------------------------------ Layer 6

(defonce ^{:stratum 6} ^{:doc "Ensures mechanical capabilities are registered exactly once
   at namespace load, so pack-gate evaluation sees them without an explicit
   caller. Callers may still invoke `register-mechanical-capabilities!`
   directly; it is idempotent."}
  ensure-registered
  (register-mechanical-capabilities!))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (register-mechanical-capabilities!)
  (policy-pack/capability-available? :lint)
  (check-syntax {:content "(defn foo [] 42"} {})
  :leave-this-here)
