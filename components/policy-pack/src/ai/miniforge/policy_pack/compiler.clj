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

(ns ai.miniforge.policy-pack.compiler
  "Compile/validate the detection binding of a policy pack.

   Miniforge's thesis is policy-as-code that is APPLIED and GUARANTEED. A rule
   whose detection cannot bind to ANY mechanism is a silent no-op — it reaches
   agents only as advisory prompt text and can never gate a run (PR #979).

   This namespace answers one question per rule: which detection MECHANISM
   does this rule bind to? — and fails LOUD at compile time if any ENABLED
   rule binds to nothing.

   `resolve-detector` reflects registry state honestly:
   - by-type mechanisms (:content-scan / :diff-analysis / :plan-output /
     :state-comparison / :ast-analysis) bind by their declared type;
   - a :capability rule binds only when its capability keyword is registered
     (mechanical capabilities are injected by the gate layer at load), else
     it is :none (unbindable — correct fail-loud);
   - a :custom rule with a resolvable :custom-fn binds to :custom;
   - a :custom rule with no resolvable :custom-fn binds to :semantic (the
     LLM-as-judge, always an available mechanism per N4-delta's :heuristic
     class);
   - anything else is :none.

   All public fns return data: a failed compilation yields an :invalid-input
   anomaly map (N4 §3.1 — anomalies are data at the component interface),
   never a throw."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-pack.capability :as capability]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0
;; Per-rule classification

(def ^{:doc "Detection types that bind to a mechanism directly by their type."}
  by-type-detectors
  #{:content-scan :diff-analysis :plan-output :state-comparison :ast-analysis})

(defn rule-enabled?
  "True unless the rule explicitly sets `:rule/enabled?` to false.
   A missing `:rule/enabled?` means enabled (the shipped pack omits it)."
  [rule]
  (not (false? (:rule/enabled? rule))))

(defn resolve-detector
  "Return the detection-mechanism keyword that `rule` binds to.

   One of the `by-type-detectors`, or `:capability` / `:custom` / `:semantic`,
   or `:none` when the rule binds to no available mechanism.

   - :capability binds only if its `:capability` keyword is registered.
   - :custom binds to :custom when its `:custom-fn` resolves, else :semantic.
   - An unknown/missing detection type is :none."
  [rule]
  (let [detection-type (get-in rule [:rule/detection :type])]
    (cond
      (contains? by-type-detectors detection-type)
      detection-type

      (= :capability detection-type)
      (let [capability-kw (get-in rule [:rule/detection :capability])]
        (if (and capability-kw (capability/capability-available? capability-kw))
          :capability
          :none))

      (= :custom detection-type)
      (if (detection/custom-fn-resolvable? rule)
        :custom
        :semantic)

      :else
      :none)))

(defn- detector-class
  [detector]
  (if (= :semantic detector)
    :heuristic
    :deterministic))

(defn- rule-metadata
  [rule detector]
  {:rule/id       (:rule/id rule)
   :rule/severity (:rule/severity rule)
   :detector      detector
   :class         (detector-class detector)})

(defn- code-files
  [artifacts]
  (or (get artifacts :code/files)
      (get artifacts :files)))

(defn- code-files-present?
  [artifacts]
  (or (contains? artifacts :code/files)
      (contains? artifacts :files)))

(defn- file-entry->artifact
  [file-entry]
  (let [path    (or (get file-entry :artifact/path)
                    (get file-entry :path))
        content (or (get file-entry :artifact/content)
                    (get file-entry :content))]
    (cond-> file-entry
      path    (assoc :artifact/path path)
      content (assoc :artifact/content content))))

(defn- normalize-artifacts
  "Normalize N4 check input into artifact maps that existing detectors accept."
  [artifacts]
  (cond
    (sequential? artifacts) (mapv file-entry->artifact artifacts)
    (code-files-present? artifacts) (mapv file-entry->artifact (code-files artifacts))
    (map? artifacts) [(file-entry->artifact artifacts)]
    :else []))

(defn- artifact-inputs
  [detector artifacts]
  (let [normalized (normalize-artifacts artifacts)]
    (if (and (= :semantic detector) (seq normalized))
      [(first normalized)]
      normalized)))

(defn- semantic-context-ready?
  [context]
  (and (fn? (:semantic-analyze-fn context))
       (some? (:llm-client context))
       (fn? (:complete-fn context))))

(defn- violation-with-rule
  [rule violation]
  (assoc violation
         :rule-id  (or (:rule-id violation) (:rule/id rule))
         :severity (or (:severity violation) (:rule/severity rule))))

(defn- detect-rule-violations
  [rule detector artifacts context]
  (->> (artifact-inputs detector artifacts)
       (keep #(detection/detect-violation rule % context))
       (mapv #(violation-with-rule rule %))))

(defn- exception-violation
  [rule detector e]
  {:type     :check-error
   :rule-id  (:rule/id rule)
   :severity (:rule/severity rule)
   :detector detector
   :error    (ex-message e)
   :message  (str "Policy check failed: " (ex-message e))})

(defn- missing-semantic-wiring-violation
  [rule]
  {:type     :semantic-error
   :rule-id  (:rule/id rule)
   :severity (:rule/severity rule)
   :message  "Semantic policy check requires :semantic-analyze-fn, :llm-client, and :complete-fn"})

(defn- check-result
  [rule detector violations]
  {:passed?    (empty? violations)
   :violations violations
   :metadata   (rule-metadata rule detector)})

(defn- compile-check-fn
  [rule detector]
  (fn [artifacts context]
    (try
      (if (and (= :semantic detector)
               (not (semantic-context-ready? context)))
        (check-result rule detector [(missing-semantic-wiring-violation rule)])
        (check-result rule detector
                      (detect-rule-violations rule detector artifacts context)))
      (catch Exception e
        (check-result rule detector [(exception-violation rule detector e)])))))

(defn compile-rule-check
  "Compile one enabled rule into an executable N4 check entry.

   Returns:
     {:rule rule
      :detector <mechanism>
      :check-fn (fn [artifacts context] -> {:passed? bool
                                            :violations [...]
                                            :metadata {...}})}

   Returns an :invalid-input anomaly when the rule cannot bind to any
   mechanism. Disabled rules are not compiled."
  [rule]
  (let [detector (resolve-detector rule)]
    (cond
      (not (rule-enabled? rule))
      (anomaly/anomaly :invalid-input
                       "Disabled rules are not compiled into checks"
                       {:rule/id (:rule/id rule)})

      (= :none detector)
      (anomaly/anomaly :invalid-input
                       "Enabled policy rule binds to no detection mechanism"
                       {:rule/id (:rule/id rule)})

      :else
      {:rule     rule
       :detector detector
       :check-fn (compile-check-fn rule detector)})))

(defn- anomaly-rule-id
  [a]
  (get-in a [:anomaly/data :rule/id]))

(defn- compiled-entry-detector
  [entry]
  (:detector entry))

;------------------------------------------------------------------------------ Layer 1
;; Pack-level validation

(defn compile-pack-checks
  "Compile every enabled rule in `pack` into executable check entries.

   Returns:
     {:ok true
      :rule-count n
      :detector-counts {...}
      :compiled-rules [{:rule ... :detector ... :check-fn fn} ...]}

   Returns an :invalid-input anomaly when pack shape is malformed or any
   enabled rule is unbindable."
  [pack]
  (if-not (sequential? (:pack/rules pack))
    (anomaly/anomaly :invalid-input
                     "Policy pack :pack/rules must be a sequential collection of rules"
                     {:pack-rules-type (some-> (:pack/rules pack) type str)})
    (let [enabled    (filter rule-enabled? (:pack/rules pack))
          compiled   (mapv compile-rule-check enabled)
          anomalies  (filter anomaly/anomaly? compiled)
          unbindable (mapv anomaly-rule-id anomalies)]
      (if (seq anomalies)
        (anomaly/anomaly :invalid-input
                         (str "Policy pack has " (count anomalies)
                              " enabled rule(s) that bind to no detection mechanism")
                         {:unbindable-rule-ids unbindable
                          :rule-count          (count enabled)})
        {:ok              true
         :rule-count      (count enabled)
         :detector-counts (frequencies (map compiled-entry-detector compiled))
         :compiled-rules  compiled}))))

(defn compile-pack
  "Validate that every ENABLED rule in `pack` binds to a detection mechanism.

   `pack` is a PackManifest map carrying `:pack/rules`.

   Returns on success a summary:
     {:ok true
      :rule-count <enabled-rule-count>
      :detector-counts {<detector-kw> n ...}}

   Returns an `:invalid-input` anomaly (data, not a throw) when one or more
   enabled rules resolve to `:none`; its `:anomaly/data` names
   `:unbindable-rule-ids` so the failure is actionable and fails loud."
  [pack]
  (let [result (compile-pack-checks pack)]
    (if (anomaly/anomaly? result)
      result
      (dissoc result :compiled-rules))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[clojure.edn :as edn])
  ;; Compile the real shipped pack — must report zero unbindable rules.
  (let [pack (edn/read-string
              (slurp "components/phase/resources/packs/miniforge-standards.pack.edn"))]
    (compile-pack pack))
  ;; => {:ok true :rule-count 49 :detector-counts {:semantic 46 :content-scan 3}}

  ;; A synthetic enabled rule with an unbindable detector fails loud.
  (compile-pack {:pack/rules [{:rule/id :bad :rule/detection {:type :nope}}]})
  ;; => {:anomaly/type :invalid-input :anomaly/data {:unbindable-rule-ids [:bad] ...}}

  :leave-this-here)
