;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.linter-runner
  "Run language-specific linters and parse output into standard violations.

   Each linter's structured output (JSON/EDN) is parsed into the unified
   violation shape used by the compliance scanner pipeline.

   Layer 0: Linter availability checking and subprocess execution
   Layer 1: Output parsers (one per linter format)
   Layer 2: Orchestration — detect languages, run available linters, merge results"
  (:require
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn- parse-json-safe
  "Parse JSON string to Clojure data, returning nil on failure."
  [s]
  (when-not (str/blank? s)
    (try
      (json/parse-string s true)
      (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 0
;; Subprocess execution

(defn linter-available?
  "Check if a linter CLI is available on PATH."
  [check-cmd]
  (try
    (zero? (:exit (process/sh {:cmd check-cmd :continue true
                               :out :string :err :string})))
    (catch Exception _ false)))

(defn- run-linter
  "Run a linter subprocess in the repo directory.
   Returns {:exit int :out string :err string}."
  [repo-path command args]
  (try
    (process/sh {:cmd (into [command] args)
                 :dir (str repo-path)
                 :continue true
                 :out :string
                 :err :string})
    (catch Exception e
      {:exit -1 :out "" :err (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 1
;; Output parsers — each returns a vector of violation maps

(defn- severity-from-level
  "Map linter severity levels to standard severity keywords."
  [level]
  (case (str/lower-case (str level))
    ("error" "e" "critical" "deny")  :critical
    ("warning" "w" "warn" "major")   :major
    ("info" "i" "note" "convention") :minor
    ("hint" "style" "refactor")      :info
    :minor))

(defn- make-violation
  "Build a standard violation map from linter output fields."
  [linter-id file line column message code severity]
  {:rule/id       (keyword (name linter-id) (or code "lint"))
   :rule/category "lint"
   :rule/title    (str (name linter-id) "/" (or code "lint"))
   :rule/severity severity
   :file          (str file)
   :line          (or line 0)
   :column        (or column 0)
   :current       (or message "")
   :suggested     nil
   :auto-fixable? false
   :rationale     (str (name linter-id) ": " (or message ""))})

;; ── Clippy (Rust) ───────────────────────────────────────────────────────────

(defn- clippy-line->violation
  "Convert a single clippy JSON line to a violation, or nil if not a compiler message."
  [line]
  (when-let [msg (parse-json-safe line)]
    (when (= "compiler-message" (get msg :reason))
      (let [m    (get msg :message)
            span (first (get m :spans))
            code (get-in m [:code :code])]
        (when (and span code)
          (make-violation :clippy
                          (get span :file_name)
                          (get span :line_start)
                          (get span :column_start)
                          (get m :message)
                          code
                          (severity-from-level (get m :level))))))))

(defn- parse-clippy
  "Parse cargo clippy --message-format=json output."
  [output]
  (->> (str/split-lines output)
       (keep clippy-line->violation)
       vec))

;; ── clj-kondo (Clojure) ────────────────────────────────────────────────────

(defn- kondo-finding->violation
  "Convert a single clj-kondo finding to a violation."
  [finding]
  (make-violation :clj-kondo
                  (get finding :filename)
                  (get finding :row)
                  (get finding :col)
                  (get finding :message)
                  (name (get finding :type :unknown))
                  (severity-from-level (name (get finding :level :warning)))))

(defn- parse-clj-kondo
  "Parse clj-kondo EDN output."
  [output]
  (try
    (let [findings (get (edn/read-string output) :findings [])]
      (mapv kondo-finding->violation findings))
    (catch Exception _ [])))

;; ── ESLint (JavaScript/TypeScript) ──────────────────────────────────────────

(defn- eslint-severity->keyword
  "Convert ESLint numeric severity (1=warn, 2=error) to keyword."
  [severity]
  (if (= 2 severity) :major :minor))

(defn- eslint-message->violation
  "Convert a single ESLint message to a violation, given the parent file path."
  [filepath msg]
  (make-violation :eslint
                  filepath
                  (get msg :line)
                  (get msg :column)
                  (get msg :message)
                  (get msg :ruleId)
                  (eslint-severity->keyword (get msg :severity))))

(defn- eslint-file->violations
  "Extract violations from a single ESLint file result."
  [file-result]
  (let [filepath (get file-result :filePath)]
    (mapv #(eslint-message->violation filepath %) (get file-result :messages []))))

(defn- parse-eslint
  "Parse eslint --format=json output."
  [output]
  (when-let [results (parse-json-safe output)]
    (when (sequential? results)
      (vec (mapcat eslint-file->violations results)))))

;; ── Ruff (Python) ──────────────────────────────────────────────────────────

(defn- ruff-violation->violation
  "Convert a single ruff violation object to a standard violation."
  [v]
  (make-violation :ruff
                  (get v :filename)
                  (get-in v [:location :row])
                  (get-in v [:location :column])
                  (get v :message)
                  (get v :code)
                  (severity-from-level (get v :type "warning"))))

(defn- parse-ruff
  "Parse ruff check --output-format=json output."
  [output]
  (when-let [results (parse-json-safe output)]
    (when (sequential? results)
      (mapv ruff-violation->violation results))))

;; ── SwiftLint (Swift) ──────────────────────────────────────────────────────

(defn- swiftlint-violation->violation
  "Convert a single SwiftLint violation object to a standard violation."
  [v]
  (make-violation :swiftlint
                  (get v :file)
                  (get v :line)
                  (get v :character)
                  (get v :reason)
                  (get v :rule_id)
                  (severity-from-level (get v :severity "warning"))))

(defn- parse-swiftlint
  "Parse swiftlint lint --reporter json output."
  [output]
  (when-let [results (parse-json-safe output)]
    (when (sequential? results)
      (mapv swiftlint-violation->violation results))))

;; ── golangci-lint (Go) ─────────────────────────────────────────────────────

(defn- golangci-issue->violation
  "Convert a single golangci-lint issue to a standard violation."
  [issue]
  (make-violation :golangci-lint
                  (get-in issue [:Pos :Filename])
                  (get-in issue [:Pos :Line])
                  (get-in issue [:Pos :Column])
                  (get issue :Text)
                  (get issue :FromLinter)
                  (severity-from-level (get issue :Severity "warning"))))

(defn- parse-golangci-lint
  "Parse golangci-lint run --out-format=json output."
  [output]
  (when-let [data (parse-json-safe output)]
    (when (map? data)
      (mapv golangci-issue->violation (get data :Issues [])))))

;; ── Parser dispatch ─────────────────────────────────────────────────────────

(def ^:private parsers
  {:clippy        parse-clippy
   :clj-kondo     parse-clj-kondo
   :eslint        parse-eslint
   :ruff          parse-ruff
   :swiftlint     parse-swiftlint
   :golangci-lint parse-golangci-lint})

(defn- parse-output
  "Parse linter output using the registered parser."
  [parser-key output]
  (if-let [parser (get parsers parser-key)]
    (or (parser output) [])
    []))

;------------------------------------------------------------------------------ Layer 2
;; Orchestration

(defn run-linter-for-tech
  "Run a single linter for a detected technology.
   Returns {:tech keyword :violations [...] :available? bool :duration-ms int}."
  [repo-path {:keys [tech/id tech/linter]}]
  (let [{:keys [command args parser check-cmd]} linter
        available? (linter-available? (or check-cmd [command "--version"]))
        start-ms   (System/currentTimeMillis)]
    (if-not available?
      {:tech id :violations [] :available? false :duration-ms 0}
      (let [output     (str (:out (run-linter repo-path command args)))
            violations (parse-output parser output)
            end-ms     (System/currentTimeMillis)]
        {:tech        id
         :violations  violations
         :available?  true
         :duration-ms (- end-ms start-ms)}))))

(defn- lintable-techs
  "Filter fingerprints to those with linters that are in the detected set."
  [fingerprints detected-techs]
  (->> fingerprints
       (filter :tech/linter)
       (filter #(contains? detected-techs (:tech/id %)))))

(defn run-all-linters
  "Run linters for all detected technologies that have a linter configured.
   Returns {:linter-results [...] :total-violations int :total-duration-ms int :violations [...]}."
  [repo-path fingerprints detected-techs]
  (let [results   (mapv #(run-linter-for-tech repo-path %) (lintable-techs fingerprints detected-techs))
        all-viols (vec (mapcat :violations results))]
    {:linter-results    results
     :total-violations  (count all-viols)
     :total-duration-ms (reduce + 0 (map :duration-ms results))
     :violations        all-viols}))

(defn- try-fix-tech
  "Attempt to run a linter's fix command. Returns {:tech id :exit code} or nil."
  [repo-path {:keys [tech/id tech/linter]}]
  (let [{:keys [command fix-args check-cmd]} linter]
    (when (linter-available? (or check-cmd [command "--version"]))
      {:tech id :exit (:exit (run-linter repo-path command fix-args))})))

(defn- fixable-techs
  "Filter fingerprints to those with fix-args that are in the detected set."
  [fingerprints detected-techs]
  (->> fingerprints
       (filter :tech/linter)
       (filter #(get-in % [:tech/linter :fix-args]))
       (filter #(contains? detected-techs (:tech/id %)))))

(defn run-linter-fixes
  "Run linter fix commands for all detected technologies.
   Returns {:fixed [...]}."
  [repo-path fingerprints detected-techs]
  {:fixed (vec (keep #(try-fix-tech repo-path %) (fixable-techs fingerprints detected-techs)))})
