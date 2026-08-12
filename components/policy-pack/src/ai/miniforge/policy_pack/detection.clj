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
(ns ai.miniforge.policy-pack.detection
  "Rule violation detection implementations.

   Layer 0: state-comparison, semantic and capability detectors,
     ast-analysis/content-scan/diff-analysis/plan-output detection,
     custom-fn registry/reflection primitives, violation-severity
     filters and formatting — pure or leaf-level, no same-file
     dependents yet
   Layer 1: custom-fn arity-reflection/registry helpers, violation
     location/message builders (over Layer 0)
   Layer 2: detector-predicate?, detect-custom, custom-fn-resolvable?,
     violation->error, detect-violation (unified per-type dispatcher)
     (over Layer 1)
   Layer 3: register-custom-fn!, check-rules (over Layer 2)

   4 real strata — over the rule 210 budget of 3; still a genuine
   namespace split (Wave 2) mid-train. Pattern-matching and
   terraform-plan-parsing primitives already moved to the sibling
   `detection.matching` namespace in this same PR; the custom-fn
   registry/reflection mechanics (`custom-fn-registry`,
   `declared-method?`, `reflectable-invoke?`, `variadic-accepts-arity?`,
   `resolve-custom-fn`, `detector-predicate?`) move to
   `detection.custom-registry` in the next PR of this train, which
   brings this namespace down to the 3-layer budget.

   Supports detection types:
   - :content-scan - Regex against artifact content
   - :diff-analysis - Regex against git diff
   - :plan-output - Parse terraform plan output
   - :ast-analysis - Structural pattern matching via tree-sitter CLI
   - :state-comparison - Drift detection via clojure.data/diff
   - :capability - Mechanical tool-gate check via the capability registry
   - :custom - Registered custom detection function (:custom-fn), else routed
     to the LLM-as-judge semantic detector"
  (:require
   [ai.miniforge.policy-pack.ast :as ast]
   [ai.miniforge.policy-pack.capability :as capability]
   [ai.miniforge.policy-pack.detection.matching :as matching]
   [ai.miniforge.policy-pack.schema-validation :as schema]
   [ai.miniforge.policy-clause.interface :as clause]
   [clojure.data :as data]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; State comparison detection
(defn ^{:stratum 0} detect-state-comparison
  "Detect violations by comparing desired state to current state.

   Uses clojure.data/diff to find structural drift between two EDN maps.
   Detects keys/values present in desired state but different or missing
   in current state.

   Arguments:
   - rule - Rule with :rule/detection config
   - artifact - Artifact (unused for state comparison)
   - context - Context with :desired-state and :current-state maps

   Returns:
   - Violation map if drift detected, nil otherwise"
  [rule _artifact context]
  (let [desired (:desired-state context)
        current (:current-state context)]
    (when (and desired current)
      (let [[only-desired only-current _both] (data/diff desired current)]
        (when (or (seq only-desired) (seq only-current))
          {:type          :state-comparison
           :rule-id       (:rule/id rule)
           :drift         {:desired-only only-desired
                           :current-only only-current}
           :message       (get-in rule [:rule/enforcement :message])})))))

;; Custom detection
(defonce ^{:stratum 0} ^{:private true
           :doc "Explicit extension registry for custom policy detectors.

Keys are the symbols stored under `:rule/detection :custom-fn`; values are
2-arity detector functions. This keeps pack manifests data-driven without
depending on ambient namespace loading or raw var resolution."}
  custom-fn-registry
  (atom {}))

(defn- ^{:stratum 0} declared-method?
  [method-name arity f]
  (some (fn [^java.lang.reflect.Method method]
          (and (= method-name (.getName method))
               (= arity (count (.getParameterTypes method)))))
        (.getDeclaredMethods (class f))))

(defn- ^{:stratum 0} reflectable-invoke?
  "True when `f`'s class declares any `invoke` method. JVM functions do; SCI
   functions under babashka do not, so arity reflection is blind there."
  [f]
  (boolean (some #(= "invoke" (.getName ^java.lang.reflect.Method %))
                 (.getDeclaredMethods (class f)))))

(defn- ^{:stratum 0} run-resolved-custom
  "Run an already-resolved custom fn against `artifact`/`context`, adapting
   the result (or a thrown exception) into a violation map, or nil on pass.
   The fn is resolved once by the caller, so neither `detect-custom` nor the
   dispatcher resolves twice.

   The rule is threaded into `context` under `:policy-pack/rule` so a detector
   can read its own `:rule/detection :detector-config` (data-driven policy) and
   enforcement message — parity with the by-type detectors, which receive the
   rule directly."
  [f rule artifact context]
  (try
    (when-let [result (f artifact (assoc context :policy-pack/rule rule))]
      (assoc result
             :type :custom
             :rule-id (:rule/id rule)))
    (catch Exception e
      {:type :custom-error
       :rule-id (:rule/id rule)
       :error (.getMessage e)
       :message (str "Custom detection failed: " (.getMessage e))})))

;; Semantic (LLM-as-judge) detection
(defn ^{:stratum 0} detect-semantic
  "Detect violations for a heuristic `:custom` rule via the LLM-as-judge.

   policy-pack is depended ON by semantic-analyzer, so this namespace cannot
   statically require it (that would be a dependency cycle) and must not
   `requiring-resolve` around the load order. Instead the gate/semantic layer
   — which already depends on both — INJECTS an `analyze-rule`-shaped fn into
   the execution context under `:semantic-analyze-fn`, alongside the
   `:llm-client`, `:complete-fn`, and `:repo-path` it needs. This mirrors the
   mechanical-capability injection pattern (see `capability` ns).

   The injected fn has the `semantic-analyzer/analyze-rule` contract:
     (fn [llm-client complete-fn repo-path rule]
       -> {:rule/id kw :violations [...] :status kw ...})

   When the semantic wiring is absent from context (no analyze-fn, no
   llm-client, or no complete-fn) this returns nil gracefully rather than
   throwing — an unbound semantic seam is a no-op at runtime, but the compiler
   (see `compiler/resolve-detector`) still classifies the rule as bindable,
   because the judge is always an available MECHANISM even when this particular
   run was not wired for it.

   On a judge result carrying violations, returns a single policy-pack
   violation tagged `:type :semantic`, `:rule-id`, and the rule's
   `:rule/severity`, carrying the judge's per-file violations through under
   `:violations`. Returns nil when the judge finds nothing.

   Arguments:
   - rule     - A `:custom` rule with no resolvable `:custom-fn`
   - artifact - Artifact being checked (unused; the judge scans repo files)
   - context  - Execution context carrying the injected semantic wiring

   Returns:
   - Violation map if the judge reports violations, nil otherwise."
  [rule _artifact context]
  (let [analyze-fn  (:semantic-analyze-fn context)
        llm-client  (:llm-client context)
        complete-fn (:complete-fn context)
        repo-path   (get context :repo-path ".")]
    (when (and (fn? analyze-fn) llm-client complete-fn)
      (try
        (let [result     (analyze-fn llm-client complete-fn repo-path rule)
              violations (:violations result)]
          (when (seq violations)
            {:type       :semantic
             :rule-id    (:rule/id rule)
             :severity   (:rule/severity rule)
             :violations (vec violations)
             :status     (:status result)
             :message    (get-in rule [:rule/enforcement :message])}))
        (catch Exception e
          ;; The judge can throw (network/API errors, malformed response). Fail
          ;; loud as data so one rule's failure can't abort the whole scan.
          {:type     :semantic-error
           :rule-id  (:rule/id rule)
           :severity (:rule/severity rule)
           :error    (.getMessage e)
           :message  (str "Semantic detection failed: " (.getMessage e))})))))

;; Capability detection (mechanical tool-gate checks via the registry)
(defn ^{:stratum 0} detect-capability
  "Detect violations by running a registered mechanical-check capability.

   Looks up `(get-in rule [:rule/detection :capability])` in the capability
   registry, runs its `:check`, and tags the resulting violation with the
   rule id and the rule's severity. The capability check-fns are injected by
   the gate layer (see `ai.miniforge.gate.capabilities`); this dispatcher
   does NOT itself depend on `loop`/`gate`.

   An unregistered capability is NOT a silent pass: it returns a
   `:type :capability-error` violation naming the missing capability, so an
   enabled rule pointing at a bad capability fails loud.

   Arguments:
   - rule     - Rule with :rule/detection containing :capability
   - artifact - Artifact being checked
   - context  - Execution context passed through to the capability check

   Returns:
   - Violation map if detected, error-violation if unregistered, nil if clean."
  [rule artifact context]
  (let [capability-kw (get-in rule [:rule/detection :capability])
        entry         (when capability-kw (capability/get-capability capability-kw))]
    (cond
      (nil? capability-kw)
      {:type          :capability-error
       :rule-id       (:rule/id rule)
       :severity      (:rule/severity rule)
       :artifact-path (or (:artifact/path artifact) (:path artifact))
       :message       "Capability detection rule is missing :capability keyword"}

      (nil? entry)
      {:type          :capability-error
       :rule-id       (:rule/id rule)
       :severity      (:rule/severity rule)
       :capability    capability-kw
       :artifact-path (or (:artifact/path artifact) (:path artifact))
       :message       (str "Capability not registered: " capability-kw)}

      :else
      (when-let [violation ((:check entry) artifact context)]
        (assoc violation
               :rule-id  (:rule/id rule)
               :severity (:rule/severity rule))))))

;; Violation classification
(defn ^{:stratum 0} classify-violations
  "Exhaustively classify `violations` by enforcement action into
   {:blocking :require-approval :warnings :audits :unknown}. There is NO
   default-pass branch: a violation whose action is off the vocabulary,
   or whose severity is off the scale, lands in `:unknown` (tagged with
   `:classify/problem`) — the pre-Ariadne per-action equality filters
   silently dropped those (the fail-open T3 calls 'the checker who
   stamps words he cannot read'). Every group is always present."
  [violations]
  (reduce
   (fn [acc v]
     (let [action (get-in v [:rule :rule/enforcement :action])
           severity (get-in v [:rule :rule/severity])
           severity-ok? (or (nil? severity) (clause/known-severity? severity))]
       (cond
         (not severity-ok?)
         (update acc :unknown conj (assoc v :classify/problem :unknown-severity))

         (= :hard-halt action)
         (update acc :blocking conj v)

         (= :require-approval action)
         (update acc :require-approval conj v)

         (= :warn action)
         (update acc :warnings conj v)

         (= :audit action)
         (update acc :audits conj v)

         :else
         (update acc :unknown conj (assoc v :classify/problem :unknown-enforcement)))))
   {:blocking [] :require-approval [] :warnings [] :audits [] :unknown []}
   violations))

(defn- ^{:stratum 0} normalize-line
  "A finding's line number, or nil when the judge omitted it. The
   semantic-analyzer's raw->violation defaults a missing `:line` to 0,
   which is not a valid editor line — carrying it forward would point the
   agent/tooling at an impossible location."
  [line]
  (when (and (integer? line) (pos? line)) line))

(defn ^{:stratum 0} violation->warning
  [{:keys [rule violation]}]
  {:code (:rule/id rule)
   :message (:message violation)
   :severity (:rule/severity rule)})

;; AST analysis detection (tree-sitter CLI)
(defn ^{:stratum 0} detect-ast-analysis
  "Detect violations by structural pattern matching via tree-sitter.

   Requires the tree-sitter CLI to be available on PATH.
   Falls back to nil (no violation) if tree-sitter is unavailable.

   Arguments:
   - rule - Rule with :rule/detection containing :query (tree-sitter S-expression pattern)
   - artifact - Artifact with :artifact/content and :artifact/path
   - context - Execution context (may contain :lang override)

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact context]
  (when (ast/tree-sitter-available?)
    (let [detection (:rule/detection rule)
          query     (or (:query detection) (first (matching/extract-patterns detection)))
          content   (:artifact/content artifact)
          path      (:artifact/path artifact "")
          lang      (ast/detect-language path (:lang context))]
      (when (and content query)
        (let [ext    (ast/file-extension path)
              result (ast/run-query content (str query) lang ext)]
          (when (and (schema/succeeded? result) (seq (:matches result)))
            {:type          :ast-analysis
             :rule-id       (:rule/id rule)
             :matches       (:matches result)
             :artifact-path path
             :message       (get-in rule [:rule/enforcement :message])}))))))

(defn ^{:stratum 0} detect-content-scan
  "Detect violations by scanning artifact content.

   Looks for pattern matches in the artifact's content field.

   Arguments:
   - rule - Rule with :rule/detection config
   - artifact - Artifact with :artifact/content
   - context - Execution context (unused for content-scan)

   Honors `:rule/detection :mode`:
   - `:positive` (default) — a pattern MATCH is a violation.
   - `:negative` — the ABSENCE of any pattern match is a violation (the rule
     requires the pattern to be present, e.g. a copyright header or a k8s
     resource limit). Applicability (file-globs, phases) scopes which artifacts
     a negative rule can flag, so a missing pattern only fires on relevant files.

   Honors `:rule/detection :multiline?`: when true the patterns are matched
   against the whole content instead of line-by-line, so a pattern that spans
   lines (`resources:\\n  limits:`) can match. Line-oriented patterns keep the
   default per-line behavior.

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact _context]
  (let [detection (:rule/detection rule)
        content (:artifact/content artifact)
        patterns (matching/extract-patterns detection)
        context-lines (:context-lines detection 2)
        mode (:mode detection :positive)
        violation (fn [matches]
                    {:type :content-scan
                     :rule-id (:rule/id rule)
                     :matches (vec matches)
                     :artifact-path (:artifact/path artifact)
                     :message (get-in rule [:rule/enforcement :message])})]
    (when (and content (seq patterns))
      (let [matches (if (:multiline? detection)
                      (matching/any-pattern-matches-multiline? patterns content)
                      (matching/any-pattern-matches? patterns content context-lines))]
        (if (= :negative mode)
          (when-not matches (violation nil))
          (when matches (violation matches)))))))

;; Diff analysis detection
(defn ^{:stratum 0} detect-diff-analysis
  "Detect violations by analyzing git diff.

   Looks for pattern matches in:
   - The artifact's diff field (if present)
   - The context's diff field

   Useful for detecting removed lines, changed patterns, etc.

   Arguments:
   - rule - Rule with :rule/detection config
   - artifact - Artifact with optional :artifact/diff
   - context - Context with optional :diff

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact context]
  (let [detection (:rule/detection rule)
        ;; Diff can be on artifact or in context
        diff (or (:artifact/diff artifact)
                 (:diff context))
        patterns (matching/extract-patterns detection)
        context-lines (:context-lines detection 3)]
    (when (and diff (seq patterns))
      (when-let [matches (matching/any-pattern-matches? patterns diff context-lines)]
        {:type :diff-analysis
         :rule-id (:rule/id rule)
         :matches matches
         :artifact-path (:artifact/path artifact)
         :message (get-in rule [:rule/enforcement :message])}))))

(defn ^{:stratum 0} detect-plan-output
  "Detect violations by analyzing terraform plan output.

   Parses plan output and checks for:
   - Resource patterns matching replace/destroy actions
   - Specific patterns in plan text

   Arguments:
   - rule - Rule with :rule/detection config
   - artifact - Artifact (may contain plan output)
   - context - Context with :terraform-plan

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact context]
  (let [detection (:rule/detection rule)
        plan-output (or (:terraform-plan context)
                        (:artifact/content artifact))
        patterns (matching/extract-patterns detection)
        resource-patterns (get-in rule [:rule/applies-to :resource-patterns])]
    (when plan-output
      ;; Two detection modes:
      ;; 1. Pattern match against raw plan text
      ;; 2. Parse plan and check resource changes
      (let [pattern-matches (when (seq patterns)
                              (matching/any-pattern-matches? patterns plan-output 2))
            ;; Parse resources and check for concerning actions
            parsed (matching/parse-plan-resources plan-output)
            resource-violations
            (when (and (seq resource-patterns) (seq parsed))
              (->> parsed
                   (filter (fn [{:keys [resource action]}]
                             (and (#{:replace :destroy} action)
                                  (some (fn [rp]
                                          (re-find (matching/ensure-pattern rp) resource))
                                        resource-patterns))))
                   seq))]
        (when (or pattern-matches resource-violations)
          {:type :plan-output
           :rule-id (:rule/id rule)
           :matches (or pattern-matches [])
           :resource-violations (vec resource-violations)
           :message (get-in rule [:rule/enforcement :message])})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} variadic-accepts-arity?
  [arity f]
  (when (declared-method? "getRequiredArity" 0 f)
    (try
      (<= (.getRequiredArity f) arity)
      (catch Throwable _ false))))

(defn ^{:stratum 1} unregister-custom-fn!
  "Remove a registered custom detector. Intended for tests and reload hygiene."
  [custom-fn-sym]
  (swap! custom-fn-registry dissoc custom-fn-sym)
  nil)

(defn- ^{:stratum 1} resolve-custom-fn
  "Look up a `:custom` rule's `:custom-fn` symbol in the explicit registry.
   Missing registrations are the no-op case routed to the judge."
  [rule]
  (when-let [custom-fn-sym (get-in rule [:rule/detection :custom-fn])]
    (get @custom-fn-registry custom-fn-sym)))

(defn- ^{:stratum 1} violation-location
  "Location for a gate finding, spanning both detector shapes. Content-scan
   / diff detectors carry top-level `:matches` + `:artifact-path`. The
   semantic (LLM-judge) detector instead nests its per-file hits — each
   `{:file :line :current}` from `semantic-analyzer` — under `:violations`,
   with no top-level `:matches`/`:artifact-path`. Reading only the top-level
   keys collapsed every semantic finding to `{:matches nil :path nil}`,
   dropping the file/line/snippet the judge produced and leaving the
   redirect agent nothing to act on. Surface the nested hits so a semantic
   finding names concrete sites like the mechanical detectors do."
  [violation]
  (if-let [hits (seq (:violations violation))]
    {:matches (mapv #(-> (select-keys % [:file :line :current :rationale])
                         (update :line normalize-line))
                    hits)
     :path    (:file (first hits))}
    {:matches (:matches violation)
     :path    (:artifact-path violation)}))

(defn- ^{:stratum 1} violation-message
  "Finding message shown to the redirect/review agent. A semantic
   violation's top-level `:message` is only the generic rule statement; the
   judge's specific per-instance findings (what/where) live in `:violations`
   (each `:rationale` is the judge's message for that site). Fold each hit
   into the message as `path:line — rationale [snippet]` so the agent sees
   the concrete instances to change, not just the rule — the other half of
   why unlocalized semantic findings couldn't be acted on. Non-semantic
   detectors keep their single `:message` unchanged."
  [violation]
  (if-let [hits (seq (:violations violation))]
    (str (:message violation)
         "\n"
         (str/join
          "\n"
          (map (fn [{:keys [file line current rationale]}]
                 (str "  - " file
                      (when-let [l (normalize-line line)] (str ":" l))
                      (when-not (str/blank? rationale) (str " — " rationale))
                      (when-not (str/blank? current) (str "  [" current "]"))))
               hits)))
    (:message violation)))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} detector-predicate?
  "True when `f` is a custom detector fn. On the JVM its 2-arity shape is verified
   by reflection; under babashka/SCI (whose fn classes expose no reflectable
   `invoke` methods, or where reflection is unavailable) arity cannot be
   introspected, so any `fn?` is accepted rather than rejecting a valid detector
   we cannot check — this lets detector namespaces register at LOAD time on both
   runtimes, instead of deferring registration to first use."
  [f]
  (and (fn? f)
       (try
         (or (declared-method? "invoke" 2 f)
             (variadic-accepts-arity? 2 f)
             (not (reflectable-invoke? f)))
         ;; Reflection/introspection failure (e.g. babashka) → accept the fn.
         ;; Catch Exception, not Throwable, so JVM Errors are not masked.
         (catch Exception _ true))))

(defn ^{:stratum 2} detect-custom
  "Detect violations using a custom function.

   The custom function is specified by registered symbol in :custom-fn and must:
   - Accept [artifact context]
   - Return violation map or nil

   Arguments:
   - rule - Rule with :rule/detection containing :custom-fn
   - artifact - Artifact being checked
   - context - Execution context

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact context]
  (when-let [f (resolve-custom-fn rule)]
    (run-resolved-custom f rule artifact context)))

(defn ^{:stratum 2} custom-fn-resolvable?
  "True when a `:custom` rule names a registered `:custom-fn` symbol.

   A `:custom` rule with no resolvable `:custom-fn` is the no-op case the
   compiled standards pack is full of (PR #979): such rules route to the
   LLM-as-judge semantic detector instead of silently never firing."
  [rule]
  (some? (resolve-custom-fn rule)))

(defn ^{:stratum 2} violation->error
  [{:keys [rule violation]}]
  {:code (:rule/id rule)
   :message (violation-message violation)
   :severity (:rule/severity rule)
   :location (violation-location violation)
   :remediation (get-in rule [:rule/enforcement :remediation])})

;; Unified detection dispatcher
(defn ^{:stratum 2} detect-violation
  "Detect violations for a rule against an artifact.

   Dispatches to the appropriate detection implementation based on
   the rule's :rule/detection :type.

   Arguments:
   - rule - Rule with :rule/detection
   - artifact - Artifact being validated
   - context - Execution context map

   Returns:
   - Violation map if detected, nil otherwise.
   - Violation map contains:
     - :type - Detection type used
     - :rule-id - Rule that was violated
     - :matches - Pattern matches found
     - :message - Enforcement message"
  [rule artifact context]
  (let [detection-type (get-in rule [:rule/detection :type])]
    (case detection-type
      :content-scan (detect-content-scan rule artifact context)
      :diff-analysis (detect-diff-analysis rule artifact context)
      :plan-output (detect-plan-output rule artifact context)
      :custom (if-let [f (resolve-custom-fn rule)]
                (run-resolved-custom f rule artifact context)
                (detect-semantic rule artifact context))
      :state-comparison (detect-state-comparison rule artifact context)
      :ast-analysis (detect-ast-analysis rule artifact context)
      :capability (detect-capability rule artifact context)
      nil)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} register-custom-fn!
  "Register `f` as the detector implementation for `custom-fn-sym`.

   Custom policy detectors are an explicit extension point. Callers that own a
   detector namespace should register the symbol they place in policy-pack EDN
   before compiling or applying packs."
  [custom-fn-sym f]
  (when-not (symbol? custom-fn-sym)
    (throw (ex-info "Custom detector key must be a symbol"
                    {:custom-fn custom-fn-sym})))
  (when-not (detector-predicate? f)
    (throw (ex-info "Custom detector value must be a two-arity predicate function"
                    {:custom-fn custom-fn-sym
                     :value-type (some-> f class .getName)})))
  (swap! custom-fn-registry assoc custom-fn-sym f)
  f)

(defn ^{:stratum 3} check-rules
  "Check multiple rules against an artifact.

   Returns vector of violations found.

   Arguments:
   - rules - Vector of rules to check
   - artifact - Artifact being validated
   - context - Execution context

   Returns:
   - Vector of violation maps, each with :rule and :violation keys"
  [rules artifact context]
  (->> rules
       (keep (fn [rule]
               (when-let [violation (detect-violation rule artifact context)]
                 {:rule rule
                  :violation violation
                  :timestamp (java.time.Instant/now)})))
       vec))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test content scan detection
  (detect-content-scan
   {:rule/id :no-todos
    :rule/detection {:type :content-scan
                     :pattern "TODO"
                     :context-lines 1}
    :rule/enforcement {:action :warn
                       :message "Found TODO"}}
   {:artifact/content "def foo():\n    # TODO: implement\n    pass"
    :artifact/path "main.py"}
   {})
  ;; => {:type :content-scan, :rule-id :no-todos, :matches [...], ...}

  ;; Test diff analysis
  (detect-diff-analysis
   {:rule/id :310-import-block-preservation
    :rule/detection {:type :diff-analysis
                     :pattern "^-\\s*import\\s*\\{"
                     :context-lines 3}
    :rule/enforcement {:action :hard-halt
                       :message "Cannot remove import blocks"}}
   {:artifact/diff "- import {\n-   to = aws_s3_bucket.example\n- }"
    :artifact/path "main.tf"}
   {})

  ;; Test plan output detection
  (detect-plan-output
   {:rule/id :320-network-recreation-block
    :rule/detection {:type :plan-output
                     :patterns ["-/\\+.*aws_route"]}
    :rule/applies-to {:resource-patterns ["aws_route" "aws_subnet"]}
    :rule/enforcement {:action :require-approval
                       :message "Network resource recreation detected"}}
   {}
   {:terraform-plan "# aws_route.main will be replaced\n  -/+ aws_route.main"})

  ;; Check multiple rules
  (check-rules
   [{:rule/id :no-todos
     :rule/detection {:type :content-scan :pattern "TODO"}
     :rule/enforcement {:action :warn :message "Found TODO"}}
    {:rule/id :no-fixmes
     :rule/detection {:type :content-scan :pattern "FIXME"}
     :rule/enforcement {:action :warn :message "Found FIXME"}}]
   {:artifact/content "# TODO: fix this\n# FIXME: broken"}
   {})

  :leave-this-here)
