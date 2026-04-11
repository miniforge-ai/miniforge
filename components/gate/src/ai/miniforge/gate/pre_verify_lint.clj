;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.gate.pre-verify-lint
  "Pre-verify lint gate — runs language-specific linters before test execution.

   Catches syntax and dependency errors in seconds instead of waiting
   for minutes of JVM startup + test execution. Uses the same linter
   configuration from tech-fingerprints.edn as `bb miniforge scan`.

   Layer 0: Linter execution
   Layer 1: Gate registration"
  (:require
   [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Linter execution

(defn- resolve-linter-fns
  "Resolve connector-linter functions. Returns nil if not available."
  []
  (try
    (let [run-all   (requiring-resolve 'ai.miniforge.connector-linter.interface/run-all)
          analyzer  (requiring-resolve 'ai.miniforge.cli.repo-analyzer/fingerprints)]
      (when (and run-all analyzer)
        {:run-all run-all :fingerprints analyzer}))
    (catch Exception _ nil)))

(defn- detect-technologies
  "Detect technologies from written file extensions."
  [artifact]
  (let [files (get-in artifact [:code/files] [])
        extensions (->> files
                        (keep (fn [f]
                                (let [path (str (get f :path ""))]
                                  (when-let [idx (clojure.string/last-index-of path ".")]
                                    (subs path (inc idx))))))
                        set)
        ext->tech {"clj" :clojure "cljs" :clojure "cljc" :clojure
                    "py" :python "rs" :rust "go" :go
                    "js" :javascript "ts" :typescript
                    "swift" :swift "rb" :ruby}]
    (into #{} (keep ext->tech) extensions)))

(defn check-pre-verify-lint
  "Run configured linters on the worktree before verify.

   Checks the repo's detected technologies against available linters
   and runs them. Returns structured errors if linting fails.

   Arguments:
     artifact - Phase artifact with :code/files
     ctx      - Execution context with :execution/worktree-path

   Returns:
     {:passed? bool :errors [...] :duration-ms int}"
  [artifact ctx]
  (let [worktree (or (get ctx :execution/worktree-path)
                     (get ctx :worktree-path)
                     ".")
        linter-fns (resolve-linter-fns)]
    (if-not linter-fns
      {:passed? true :errors [] :message "Linter not available — skipped"}
      (let [techs     (detect-technologies artifact)
            fps       @(:fingerprints linter-fns)
            result    ((:run-all linter-fns) worktree fps techs)
            violations (:violations result)]
        (if (empty? violations)
          {:passed?     true
           :errors      []
           :duration-ms (get result :total-duration-ms 0)
           :message     (str "Lint clean (" (get result :total-duration-ms 0) "ms)")}
          {:passed? false
           :errors  (mapv (fn [v]
                            {:type     :lint-error
                             :file     (get v :file)
                             :line     (get v :line 0)
                             :message  (get v :current "")
                             :rule-id  (get v :rule/id)
                             :severity (get v :rule/severity :major)})
                          violations)
           :duration-ms (get result :total-duration-ms 0)})))))

(defn repair-pre-verify-lint
  "Lint errors cannot be auto-repaired by the gate — return to agent."
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors   errors
   :message  "Lint errors require agent repair — returning to implement phase"})

;------------------------------------------------------------------------------ Layer 1
;; Gate registration

(registry/register-gate! :pre-verify-lint)

(defmethod registry/get-gate :pre-verify-lint
  [_]
  {:name        :pre-verify-lint
   :description "Run language linters before test execution to catch errors fast"
   :check       check-pre-verify-lint
   :repair      repair-pre-verify-lint})
