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
    (let [result (process/sh {:cmd check-cmd :continue true
                              :out :string :err :string})]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn- run-linter
  "Run a linter subprocess in the repo directory.
   Returns {:exit int :out string :err string}."
  [repo-path command args]
  (try
    (let [result (process/sh {:cmd (into [command] args)
                              :dir (str repo-path)
                              :continue true
                              :out :string
                              :err :string})]
      result)
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

(defn- parse-clippy
  "Parse cargo clippy --message-format=json output.
   Each line is a separate JSON object. Filter for compiler-message types."
  [output]
  (->> (str/split-lines output)
       (keep (fn [line]
               (when-let [msg (parse-json-safe line)]
                 (when (= "compiler-message" (get msg :reason))
                   (let [m    (get msg :message)
                         span (first (get m :spans))
                         code (get-in m [:code :code])]
                     (when (and span code)
                       (make-violation
                        :clippy
                        (get span :file_name)
                        (get span :line_start)
                        (get span :column_start)
                        (get m :message)
                        code
                        (severity-from-level (get m :level)))))))))
       vec))

;; ── clj-kondo (Clojure) ────────────────────────────────────────────────────

(defn- parse-clj-kondo
  "Parse clj-kondo EDN output. Output is a map with :findings vector."
  [output]
  (try
    (let [data     (edn/read-string output)
          findings (get data :findings [])]
      (mapv (fn [f]
              (make-violation
               :clj-kondo
               (get f :filename)
               (get f :row)
               (get f :col)
               (get f :message)
               (name (get f :type :unknown))
               (severity-from-level (name (get f :level :warning)))))
            findings))
    (catch Exception _ [])))

;; ── ESLint (JavaScript/TypeScript) ──────────────────────────────────────────

(defn- parse-eslint
  "Parse eslint --format=json output. Array of file results."
  [output]
  (let [results (parse-json-safe output)]
    (when (sequential? results)
      (->> results
           (mapcat (fn [file-result]
                     (let [filepath (get file-result :filePath)]
                       (map (fn [msg]
                              (make-violation
                               :eslint
                               filepath
                               (get msg :line)
                               (get msg :column)
                               (get msg :message)
                               (get msg :ruleId)
                               (if (= 2 (get msg :severity)) :major :minor)))
                            (get file-result :messages [])))))
           vec))))

;; ── Ruff (Python) ──────────────────────────────────────────────────────────

(defn- parse-ruff
  "Parse ruff check --output-format=json output. Array of violation objects."
  [output]
  (let [results (parse-json-safe output)]
    (when (sequential? results)
      (mapv (fn [v]
              (make-violation
               :ruff
               (get v :filename)
               (get-in v [:location :row])
               (get-in v [:location :column])
               (get v :message)
               (get v :code)
               (severity-from-level (get v :type "warning"))))
            results))))

;; ── SwiftLint (Swift) ──────────────────────────────────────────────────────

(defn- parse-swiftlint
  "Parse swiftlint lint --reporter json output. Array of violation objects."
  [output]
  (let [results (parse-json-safe output)]
    (when (sequential? results)
      (mapv (fn [v]
              (make-violation
               :swiftlint
               (get v :file)
               (get v :line)
               (get v :character)
               (get v :reason)
               (get v :rule_id)
               (severity-from-level (get v :severity "warning"))))
            results))))

;; ── golangci-lint (Go) ─────────────────────────────────────────────────────

(defn- parse-golangci-lint
  "Parse golangci-lint run --out-format=json output."
  [output]
  (let [data (parse-json-safe output)]
    (when (map? data)
      (->> (get data :Issues [])
           (mapv (fn [issue]
                   (make-violation
                    :golangci-lint
                    (get-in issue [:Pos :Filename])
                    (get-in issue [:Pos :Line])
                    (get-in issue [:Pos :Column])
                    (get issue :Text)
                    (get issue :FromLinter)
                    (severity-from-level (get issue :Severity "warning")))))))))

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
      (let [result     (run-linter repo-path command args)
            output     (str (:out result))
            violations (parse-output parser output)
            end-ms     (System/currentTimeMillis)]
        {:tech        id
         :violations  violations
         :available?  true
         :duration-ms (- end-ms start-ms)}))))

(defn run-all-linters
  "Run linters for all detected technologies that have a linter configured.
   Returns {:linter-results [...] :total-violations int :total-duration-ms int}."
  [repo-path fingerprints detected-techs]
  (let [lintable (->> fingerprints
                      (filter :tech/linter)
                      (filter #(contains? detected-techs (:tech/id %))))
        results  (mapv #(run-linter-for-tech repo-path %) lintable)
        all-viols (mapcat :violations results)]
    {:linter-results    results
     :total-violations  (count all-viols)
     :total-duration-ms (reduce + 0 (map :duration-ms results))
     :violations        (vec all-viols)}))

(defn run-linter-fixes
  "Run linter fix commands for all detected technologies.
   Returns {:fixed [...] :failed [...]}."
  [repo-path fingerprints detected-techs]
  (let [fixable (->> fingerprints
                     (filter :tech/linter)
                     (filter #(get-in % [:tech/linter :fix-args]))
                     (filter #(contains? detected-techs (:tech/id %))))]
    {:fixed
     (vec
      (keep (fn [{:keys [tech/id tech/linter]}]
              (let [{:keys [command fix-args check-cmd]} linter]
                (when (linter-available? (or check-cmd [command "--version"]))
                  (let [result (run-linter repo-path command fix-args)]
                    {:tech id :exit (:exit result)}))))
            fixable))}))
