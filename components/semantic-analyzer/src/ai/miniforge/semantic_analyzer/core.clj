;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.semantic-analyzer.core
  "LLM-as-judge semantic analysis of source code against behavioral rules.

   Sends file content + rule knowledge to an LLM, parses structured
   violation output, and produces canonical violation maps.

   Layer 0: Prompt construction from templates
   Layer 1: LLM invocation and response parsing
   Layer 2: File selection and batch analysis"
  (:require
   [ai.miniforge.policy-pack.prompt-template :as pt]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt construction

(def ^:private judge-prompt-resource "semantic_analyzer/judge-prompt.edn")

(def ^:private judge-templates
  "Judge prompt templates loaded from classpath."
  (delay
    (if-let [url (io/resource judge-prompt-resource)]
      (edn/read-string (slurp url))
      {:system "You are a code reviewer. Return EDN violations or []."
       :user "Rule: {{rule-title}}\n\n{{knowledge-content}}\n\nFile: {{file-path}}\n```\n{{file-content}}\n```"})))

(defn build-judge-prompt
  "Build system + user prompts for LLM-as-judge analysis.
   Returns {:system string :user string}."
  [rule file-path file-content]
  (let [templates @judge-templates
        bindings  {:rule-title        (get rule :rule/title "")
                   :rule-description  (get rule :rule/description "")
                   :knowledge-content (get rule :rule/knowledge-content "")
                   :file-path         file-path
                   :file-content      file-content}]
    {:system (pt/interpolate (get templates :system) bindings)
     :user   (pt/interpolate (get templates :user) bindings)}))

;------------------------------------------------------------------------------ Layer 1
;; LLM invocation and parsing

(defn- parse-judge-response
  "Parse the LLM's EDN response into violation maps.
   Returns a vector of violations, or empty vector on parse failure."
  [response-text]
  (try
    (let [trimmed (str/trim (str response-text))
          ;; Strip markdown code fences if present
          cleaned (cond-> trimmed
                    (str/starts-with? trimmed "```") (str/replace #"^```\w*\n?" "")
                    (str/ends-with? trimmed "```")   (str/replace #"\n?```$" ""))]
      (let [parsed (edn/read-string (str/trim cleaned))]
        (if (vector? parsed)
          (filterv map? parsed)
          [])))
    (catch Exception _ [])))

(defn- judge-response->violations
  "Convert parsed judge response to canonical violation maps."
  [rule file-path raw-violations]
  (mapv (fn [v]
          {:rule/id       (get rule :rule/id)
           :rule/category (get rule :rule/category "000")
           :rule/title    (get rule :rule/title)
           :rule/severity (get rule :rule/severity :info)
           :file          file-path
           :line          (get v :line 0)
           :current       (get v :current "")
           :suggested     nil
           :auto-fixable? false
           :rationale     (get v :message "Semantic analysis violation")})
        raw-violations))

(defn analyze-file
  "Analyze a single file against a behavioral rule using the LLM.

   Arguments:
   - llm-client — LLM client from ai.miniforge.llm.interface
   - complete-fn — Function to call LLM: (fn [client request]) -> response
   - rule — Policy pack rule with :rule/knowledge-content
   - file-path — Path to the file
   - file-content — File content string

   Returns:
   - Vector of canonical violation maps"
  [llm-client complete-fn rule file-path file-content]
  (let [{:keys [system user]} (build-judge-prompt rule file-path file-content)
        response (complete-fn llm-client
                              {:system   system
                               :messages [{:role "user" :content user}]})
        content  (get response :content "")]
    (judge-response->violations rule file-path
                                (parse-judge-response content))))

;------------------------------------------------------------------------------ Layer 2
;; File selection and batch analysis

(def ^:private max-file-size-bytes
  "Maximum file size to send to LLM. Files larger than this are skipped."
  50000)

(def ^:private max-files-per-rule
  "Maximum number of files to analyze per rule to control LLM costs."
  20)

(defn- file-matches-globs?
  "Check if a file path matches any of the rule's file globs."
  [file-path globs]
  (let [fs (java.nio.file.FileSystems/getDefault)]
    (some (fn [glob]
            (.matches (.getPathMatcher fs (str "glob:" glob))
                      (java.nio.file.Paths/get file-path (into-array String []))))
          globs)))

(defn select-files-for-rule
  "Select files from repo that match a rule's file globs.
   Limits to max-files-per-rule, skips files over max-file-size-bytes."
  [repo-path rule]
  (let [globs (get-in rule [:rule/applies-to :file-globs] ["**/*"])
        all-files (->> (file-seq (io/file repo-path))
                       (filter #(.isFile %))
                       (filter #(< (.length %) max-file-size-bytes))
                       (filter #(file-matches-globs?
                                 (.toString (.relativize
                                             (.toPath (io/file repo-path))
                                             (.toPath %)))
                                 globs)))]
    (take max-files-per-rule all-files)))

(defn analyze-rule
  "Analyze all matching files against a single behavioral rule.

   Arguments:
   - llm-client — LLM client
   - complete-fn — LLM completion function
   - repo-path — Repository root path
   - rule — Behavioral rule with :rule/knowledge-content

   Returns:
   - {:rule/id keyword :violations [...] :files-analyzed int :duration-ms int}"
  [llm-client complete-fn repo-path rule]
  (let [start-ms (System/currentTimeMillis)
        files    (select-files-for-rule repo-path rule)
        viols    (->> files
                      (mapcat (fn [f]
                                (let [rel-path (.toString (.relativize
                                                           (.toPath (io/file repo-path))
                                                           (.toPath f)))
                                      content  (slurp f)]
                                  (analyze-file llm-client complete-fn rule rel-path content))))
                      vec)
        end-ms   (System/currentTimeMillis)]
    {:rule/id        (get rule :rule/id)
     :violations     viols
     :files-analyzed (count files)
     :duration-ms    (- end-ms start-ms)}))

(defn behavioral-rules
  "Filter rules to those with :rule/knowledge-content (behavioral/semantic rules).
   These are the rules that need LLM analysis, not mechanical scanning."
  [pack-rules]
  (filterv :rule/knowledge-content pack-rules))
