;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.gate.pre-verify-lint
  "Pre-verify lint gate — runs language-specific linters before test execution.

   Catches syntax and dependency errors in seconds instead of waiting
   for minutes of JVM startup + test execution. Uses the same linter
   configuration from tech-fingerprints.edn as `bb miniforge scan`.

   Layer 0: Technology detection and linter execution
   Layer 1: Gate registration"
  (:require
   [ai.miniforge.connector-linter.interface :as linter]
   [ai.miniforge.cli.repo-analyzer :as analyzer]
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.response.builder :as response]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Technology detection

(def ^:private ext->tech
  "File extension to technology mapping."
  {"clj" :clojure "cljs" :clojure "cljc" :clojure
   "py" :python "rs" :rust "go" :go
   "js" :javascript "ts" :typescript
   "swift" :swift "rb" :ruby})

(defn- file-extension
  "Extract extension from a file path."
  [path]
  (when-let [idx (str/last-index-of (str path) ".")]
    (subs (str path) (inc idx))))

(defn- file->tech
  "Map a file entry to its technology keyword, or nil."
  [file-entry]
  (get ext->tech (file-extension (get file-entry :path ""))))

(defn- detect-technologies
  "Detect technologies from written file extensions."
  [artifact]
  (into #{} (keep file->tech) (get artifact :code/files [])))

;------------------------------------------------------------------------------ Layer 0
;; Linter execution

(defn- resolve-worktree
  "Get the worktree path from context."
  [ctx]
  (or (get ctx :execution/worktree-path)
      (get ctx :worktree-path)
      "."))

(defn- violation->lint-error
  "Convert a linter violation to a gate error map."
  [v]
  {:type     :lint-error
   :file     (get v :file)
   :line     (get v :line 0)
   :message  (get v :current "")
   :rule-id  (get v :rule/id)
   :severity (get v :rule/severity :major)})

(defn check-pre-verify-lint
  "Run configured linters on the worktree before verify."
  [artifact ctx]
  (let [worktree   (resolve-worktree ctx)
        techs      (detect-technologies artifact)
        fps        @analyzer/fingerprints
        result     (linter/run-all worktree fps techs)
        violations (:violations result)
        duration   (get result :total-duration-ms 0)]
    (if (empty? violations)
      {:passed? true}
      {:passed? false
       :errors (mapv violation->lint-error violations)})))

(defn repair-pre-verify-lint
  "Lint errors cannot be auto-repaired — return to agent."
  [artifact errors _ctx]
  {:success? false
   :errors errors})

;------------------------------------------------------------------------------ Layer 1
;; Gate registration

(registry/register-gate! :pre-verify-lint)

(defmethod registry/get-gate :pre-verify-lint
  [_]
  {:name        :pre-verify-lint
   :description "Run language linters before test execution to catch errors fast"
   :check       check-pre-verify-lint
   :repair      repair-pre-verify-lint})
