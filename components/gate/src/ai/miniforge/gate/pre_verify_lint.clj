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
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.repo-analyzer.interface :as repo-analyzer]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Technology detection
(def ^{:stratum 0} ^:private ext->tech
  "File extension to technology mapping."
  {"clj" :clojure "cljs" :clojure "cljc" :clojure
   "py" :python "rs" :rust "go" :go
   "js" :javascript "ts" :typescript
   "swift" :swift "rb" :ruby})

(defn- ^{:stratum 0} file-extension
  "Extract extension from a file path."
  [path]
  (when-let [idx (str/last-index-of (str path) ".")]
    (subs (str path) (inc idx))))

;; Linter execution
(defn- ^{:stratum 0} resolve-worktree
  "Get the worktree path from context."
  [ctx]
  (or (get ctx :execution/worktree-path)
      (get ctx :worktree-path)
      "."))

(defn- ^{:stratum 0} violation->lint-error
  "Convert a linter violation to a gate error map."
  [v]
  {:type     :lint-error
   :file     (get v :file)
   :line     (get v :line 0)
   :message  (get v :current "")
   :rule-id  (get v :rule/id)
   :severity (get v :rule/severity :high)})

(defn ^{:stratum 0} repair-pre-verify-lint
  "Lint errors cannot be auto-repaired — return to agent."
  [_artifact errors _ctx]
  {:success? false
   :errors errors})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} file->tech
  "Map a file entry to its technology keyword, or nil."
  [file-entry]
  (get ext->tech (file-extension (get file-entry :path ""))))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} detect-technologies
  "Detect technologies from written file extensions."
  [artifact]
  (into #{} (keep file->tech) (get artifact :code/files [])))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} check-pre-verify-lint
  "Run configured linters on the worktree before verify."
  [artifact ctx]
  (let [worktree   (resolve-worktree ctx)
        techs      (detect-technologies artifact)
        fps        @repo-analyzer/fingerprints
        result     (linter/run-all worktree fps techs)
        violations (:violations result)]
    (if (empty? violations)
      {:passed? true}
      {:passed? false
       :errors (mapv violation->lint-error violations)})))

;------------------------------------------------------------------------------ Layer 4

(defmethod ^{:stratum 4} registry/get-gate :pre-verify-lint
  [_]
  {:name        :pre-verify-lint
   :description "Run language linters before test execution to catch errors fast"
   :check       check-pre-verify-lint
   :repair      repair-pre-verify-lint})

;; Gate registration
(registry/register-gate! :pre-verify-lint)
