(ns ai.miniforge.loop.gates
  "Validation gate protocol and built-in gate implementations.

   Note: The Gate protocol has been moved to:
   - ai.miniforge.loop.interface.protocols.gate (Gate protocol)

   This namespace contains the built-in gate implementations.
   Layer 0: Pure functions for gate results
   Layer 1: Built-in gates (syntax, lint, test, policy)
   Layer 2: Gate runner and composition"
  (:require
   [ai.miniforge.loop.interface.protocols.gate :as p]
   [ai.miniforge.logging.interface :as log]))

;; Re-export protocol for backward compatibility
(def Gate p/Gate)
(def check p/check)
(def gate-id p/gate-id)
(def gate-type p/gate-type)
(def repair p/repair)

;------------------------------------------------------------------------------ Layer 0
;; Gate result constructors (pure functions)

(defn pass-result
  "Create a passing gate result."
  [gate-id gate-type & {:keys [warnings duration-ms]}]
  (cond-> {:gate/id gate-id
           :gate/type gate-type
           :gate/passed? true}
    warnings (assoc :gate/warnings warnings)
    duration-ms (assoc :gate/duration-ms duration-ms)))

(defn fail-result
  "Create a failing gate result."
  [gate-id gate-type errors & {:keys [warnings duration-ms]}]
  (cond-> {:gate/id gate-id
           :gate/type gate-type
           :gate/passed? false
           :gate/errors errors}
    warnings (assoc :gate/warnings warnings)
    duration-ms (assoc :gate/duration-ms duration-ms)))

(defn make-error
  "Create a gate error map."
  [code message & {:keys [file line column]}]
  (cond-> {:code code
           :message message}
    file (assoc :location (cond-> {:file file}
                            line (assoc :line line)
                            column (assoc :column column)))))

;------------------------------------------------------------------------------ Layer 1
;; Built-in gates

(defrecord SyntaxGate [id config]
  p/Gate
  (check [_this artifact context]
    (let [start (System/currentTimeMillis)
          content (:artifact/content artifact)
          artifact-type (:artifact/type artifact)
          logger (:logger context)]
      (when logger
        (log/debug logger :loop :policy/gate-evaluated
                   {:data {:gate-id id :artifact-type artifact-type}}))
      (try
        ;; For Clojure code, try to read the content
        (if (and (#{:code :test} artifact-type)
                 (string? content)
                 (seq content))
          (do
            #_{:clj-kondo/ignore [:unused-value]}
            (read-string content)  ; Parse to check syntax; result intentionally unused
            (pass-result id :syntax
                         :duration-ms (- (System/currentTimeMillis) start)))
          ;; For non-code artifacts, pass by default
          (pass-result id :syntax
                       :duration-ms (- (System/currentTimeMillis) start)))
        (catch Exception e
          (fail-result id :syntax
                       [(make-error :syntax-error (.getMessage e))]
                       :duration-ms (- (System/currentTimeMillis) start))))))

  (gate-id [_this] id)
  (gate-type [_this] :syntax)

  (repair [_this _artifact violations _context]
    ;; Syntax errors require LLM-based repair
    {:repaired? false
     :changes []
     :remaining-violations violations}))

(defn syntax-gate
  "Create a syntax validation gate.
   Checks that code artifacts can be parsed without errors."
  ([] (syntax-gate :syntax-check {}))
  ([id] (syntax-gate id {}))
  ([id config] (->SyntaxGate id config)))


(defrecord LintGate [id config]
  p/Gate
  (check [_this artifact context]
    (let [start (System/currentTimeMillis)
          content (:artifact/content artifact)
          artifact-type (:artifact/type artifact)
          logger (:logger context)
          ;; For now, we do basic linting checks
          ;; In production, this would call clj-kondo or similar
          fail-on-warning? (:fail-on-warning? config false)]
      (when logger
        (log/debug logger :loop :policy/gate-evaluated
                   {:data {:gate-id id :artifact-type artifact-type}}))
      (if (and (#{:code :test} artifact-type)
               (string? content))
        ;; Basic lint checks (placeholder for real linter integration)
        (let [warnings (cond-> []
                         ;; Check for println (common debug leftover)
                         (re-find #"\(println\s" content)
                         (conj (make-error :debug-println
                                           "Debug println found in code"))

                         ;; Check for unused :require
                         (re-find #":as\s+_\]" content)
                         (conj (make-error :unused-require
                                           "Unused namespace alias found")))]
          (if (and fail-on-warning? (seq warnings))
            (fail-result id :lint warnings
                         :duration-ms (- (System/currentTimeMillis) start))
            (pass-result id :lint
                         :warnings (when (seq warnings) warnings)
                         :duration-ms (- (System/currentTimeMillis) start))))
        ;; Non-code artifacts pass lint
        (pass-result id :lint
                     :duration-ms (- (System/currentTimeMillis) start)))))

  (gate-id [_this] id)
  (gate-type [_this] :lint)

  (repair [_this artifact violations _context]
    ;; Basic lint auto-fixes (expand as needed)
    (let [content (:artifact/content artifact)
          ;; Remove println statements
          fixed-content (when (string? content)
                          (clojure.string/replace content #"\(println\s[^\)]+\)\n?" ""))
          changes (if (and (string? content) (not= content fixed-content))
                    [:removed-debug-println]
                    [])
          remaining (if (seq changes)
                      (filter #(not= :debug-println (:code %)) violations)
                      violations)]
      {:repaired? (seq changes)
       :artifact (assoc artifact :artifact/content fixed-content)
       :changes changes
       :remaining-violations remaining})))

(defn lint-gate
  "Create a lint validation gate.
   Options:
   - :fail-on-warning? - If true, warnings cause failure (default false)"
  ([] (lint-gate :lint-check {}))
  ([id] (lint-gate id {}))
  ([id config] (->LintGate id config)))


(defrecord TestGate [id config]
  p/Gate
  (check [_this artifact context]
    (let [start (System/currentTimeMillis)
          logger (:logger context)
          test-fn (:test-fn config)
          artifact-type (:artifact/type artifact)]
      (when logger
        (log/debug logger :loop :policy/gate-evaluated
                   {:data {:gate-id id :artifact-type artifact-type}}))
      (if test-fn
        ;; Execute provided test function
        (try
          (let [result (test-fn artifact context)]
            (if (:passed? result)
              (pass-result id :test
                           :duration-ms (- (System/currentTimeMillis) start))
              (fail-result id :test
                           (or (:errors result)
                               [(make-error :test-failed "Test execution failed")])
                           :duration-ms (- (System/currentTimeMillis) start))))
          (catch Exception e
            (fail-result id :test
                         [(make-error :test-error (.getMessage e))]
                         :duration-ms (- (System/currentTimeMillis) start))))
        ;; No test function provided, pass by default
        (pass-result id :test
                     :warnings [(make-error :no-test-fn "No test function configured")]
                     :duration-ms (- (System/currentTimeMillis) start)))))

  (gate-id [_this] id)
  (gate-type [_this] :test)

  (repair [_this _artifact violations _context]
    ;; Test failures require code changes by implementer agent
    {:repaired? false
     :changes []
     :remaining-violations violations}))

(defn test-gate
  "Create a test validation gate.
   Options:
   - :test-fn - Function (fn [artifact context] -> {:passed? bool :errors [...]})"
  ([] (test-gate :test-check {}))
  ([id] (test-gate id {}))
  ([id config] (->TestGate id config)))


(defrecord PolicyGate [id config]
  p/Gate
  (check [_this artifact context]
    (let [start (System/currentTimeMillis)
          logger (:logger context)
          policies (:policies config [])
          artifact-type (:artifact/type artifact)
          content (:artifact/content artifact)]
      (when logger
        (log/debug logger :loop :policy/gate-evaluated
                   {:data {:gate-id id :artifact-type artifact-type :policies policies}}))
      ;; Run each policy check
      (let [errors (reduce
                    (fn [acc policy]
                      (case policy
                        ;; No hardcoded secrets
                        :no-secrets
                        (if (and (string? content)
                                 (or (re-find #"(?i)(api[_-]?key|secret|password)\s*=\s*['\"][^'\"]+['\"]" content)
                                     (re-find #"(?i)(bearer|token)\s+[a-zA-Z0-9]{20,}" content)))
                          (conj acc (make-error :hardcoded-secret
                                                "Possible hardcoded secret detected"))
                          acc)

                        ;; No TODO comments in production code
                        :no-todos
                        (if (and (string? content)
                                 (re-find #"(?i)\b(TODO|FIXME|HACK|XXX)\b" content))
                          (conj acc (make-error :todo-found
                                                "TODO/FIXME comment found in code"))
                          acc)

                        ;; Require docstrings
                        :require-docstrings
                        (if (and (string? content)
                                 (#{:code} artifact-type)
                                 (re-find #"\(defn\s+\S+\s+\[" content))
                          (conj acc (make-error :missing-docstring
                                                "Public function missing docstring"))
                          acc)

                        ;; Unknown policy, ignore
                        acc))
                    []
                    policies)]
        (if (seq errors)
          (fail-result id :policy errors
                       :duration-ms (- (System/currentTimeMillis) start))
          (pass-result id :policy
                       :duration-ms (- (System/currentTimeMillis) start))))))

  (gate-id [_this] id)
  (gate-type [_this] :policy)

  (repair [_this artifact violations _context]
    ;; Policy violations may have automatic fixes
    (let [content (:artifact/content artifact)
          ;; Remove TODO comments
          fixed-content (when (string? content)
                          (clojure.string/replace content #"(?m)^\s*;;.*\b(TODO|FIXME|HACK|XXX)\b.*$\n?" ""))
          changes (if (and (string? content) (not= content fixed-content))
                    [:removed-todo-comments]
                    [])
          remaining (if (seq changes)
                      (filter #(not= :todo-found (:code %)) violations)
                      violations)]
      {:repaired? (seq changes)
       :artifact (assoc artifact :artifact/content fixed-content)
       :changes changes
       :remaining-violations remaining})))

(defn policy-gate
  "Create a policy validation gate.
   Options:
   - :policies - Vector of policy keywords to check
                 Supported: :no-secrets, :no-todos, :require-docstrings"
  ([] (policy-gate :policy-check {}))
  ([id] (policy-gate id {}))
  ([id config] (->PolicyGate id config)))


(defrecord CustomGate [id type-kw check-fn]
  p/Gate
  (check [_this artifact context]
    (let [start (System/currentTimeMillis)]
      (try
        (let [result (check-fn artifact context)]
          (if (:gate/passed? result)
            (assoc result :gate/duration-ms (- (System/currentTimeMillis) start))
            (assoc result :gate/duration-ms (- (System/currentTimeMillis) start))))
        (catch Exception e
          (fail-result id :custom
                       [(make-error :custom-gate-error (.getMessage e))]
                       :duration-ms (- (System/currentTimeMillis) start))))))

  (gate-id [_this] id)
  (gate-type [_this] type-kw)

  (repair [_this _artifact violations _context]
    ;; Custom gates don't have default repair logic
    {:repaired? false
     :changes []
     :remaining-violations violations}))

(defn custom-gate
  "Create a custom validation gate.
   Arguments:
   - id - Unique gate identifier
   - check-fn - Function (fn [artifact context] -> gate-result-map)"
  ([id check-fn] (custom-gate id :custom check-fn))
  ([id type-kw check-fn] (->CustomGate id type-kw check-fn)))

;------------------------------------------------------------------------------ Layer 2
;; Gate runner

(defn run-gate
  "Run a single gate check, handling errors gracefully.
   Returns the gate result map."
  [gate artifact context]
  (try
    (check gate artifact context)
    (catch Exception e
      (fail-result (gate-id gate) (gate-type gate)
                   [(make-error :gate-execution-error
                                (str "Gate execution failed: " (.getMessage e)))]))))

(defn run-gates
  "Run multiple gates against an artifact.
   Options:
   - :fail-fast? - Stop on first failure (default false)
   - :logger - Logger instance for gate execution logging

   Returns:
   {:passed? bool
    :results [gate-result...]
    :failed-gates [gate-id...]
    :errors [error...] ; aggregated errors from failed gates}"
  [gates artifact context & {:keys [fail-fast?] :or {fail-fast? false}}]
  (let [logger (:logger context)]
    (when logger
      (log/debug logger :loop :inner/validation-passed
                 {:message "Starting gate validation"
                  :data {:gate-count (count gates)}}))
    (loop [remaining gates
           results []
           failed-gates []
           all-errors []]
      (if (empty? remaining)
        ;; All gates run
        {:passed? (empty? failed-gates)
         :results results
         :failed-gates failed-gates
         :errors all-errors}
        ;; Run next gate
        (let [gate (first remaining)
              result (run-gate gate artifact context)
              new-results (conj results result)]
          (if (:gate/passed? result)
            ;; Gate passed, continue
            (recur (rest remaining) new-results failed-gates all-errors)
            ;; Gate failed
            (let [new-failed (conj failed-gates (gate-id gate))
                  new-errors (into all-errors (:gate/errors result))]
              (if fail-fast?
                ;; Stop on first failure
                {:passed? false
                 :results new-results
                 :failed-gates new-failed
                 :errors new-errors}
                ;; Continue running remaining gates
                (recur (rest remaining) new-results new-failed new-errors)))))))))

(defn applicable-gates
  "Filter gates to only those applicable to the given artifact type.
   If a gate has no :applies-to config, it applies to all artifacts."
  [gates artifact]
  (let [artifact-type (:artifact/type artifact)]
    (filter
     (fn [gate]
       (let [config (when (satisfies? clojure.lang.ILookup gate)
                      (:config gate))
             applies-to (when config (:applies-to config))]
         (or (nil? applies-to)
             (contains? applies-to artifact-type))))
     gates)))

;------------------------------------------------------------------------------ Layer 2
;; Gate set constructors

(defn default-gates
  "Create a default set of gates for code artifacts.
   Options:
   - :lint-fail-on-warning? - Lint gate fails on warnings (default false)
   - :policies - Vector of policy keywords (default [:no-secrets])"
  [& {:keys [lint-fail-on-warning? policies]
      :or {lint-fail-on-warning? false
           policies [:no-secrets]}}]
  [(syntax-gate)
   (lint-gate :lint-check {:fail-on-warning? lint-fail-on-warning?})
   (policy-gate :policy-check {:policies policies})])

(defn minimal-gates
  "Create a minimal set of gates (syntax only)."
  []
  [(syntax-gate)])

(defn strict-gates
  "Create a strict set of gates for production code.
   Includes all policies and fails on warnings."
  []
  [(syntax-gate)
   (lint-gate :lint-check {:fail-on-warning? true})
   (policy-gate :policy-check {:policies [:no-secrets :no-todos :require-docstrings]})])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create gates
  (def syntax (syntax-gate))
  (def lint (lint-gate :my-lint {:fail-on-warning? true}))
  (def policy (policy-gate :my-policy {:policies [:no-secrets :no-todos]}))

  ;; Check artifact
  (def artifact {:artifact/id (random-uuid)
                 :artifact/type :code
                 :artifact/content "(defn hello [] \"world\")"})

  (check syntax artifact {})
  ;; => {:gate/id :syntax-check, :gate/type :syntax, :gate/passed? true, ...}

  ;; Check invalid syntax
  (check syntax {:artifact/id (random-uuid)
                 :artifact/type :code
                 :artifact/content "(defn hello ["} {})
  ;; => {:gate/id :syntax-check, :gate/type :syntax, :gate/passed? false, :gate/errors [...]}

  ;; Run multiple gates
  (run-gates (default-gates) artifact {})
  ;; => {:passed? true, :results [...], :failed-gates [], :errors []}

  ;; Create custom gate
  (def length-gate
    (custom-gate :max-length
                 (fn [artifact _ctx]
                   (let [content (:artifact/content artifact)]
                     (if (and (string? content) (> (count content) 1000))
                       (fail-result :max-length :custom
                                    [(make-error :too-long "Content exceeds 1000 chars")])
                       (pass-result :max-length :custom))))))

  (check length-gate artifact {})

  :leave-this-here)
