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
   [ai.miniforge.compliance-scanner.factory :as factory]
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

(defn- rule->prompt-bindings
  "Extract prompt interpolation bindings from a rule and file."
  [rule file-path file-content]
  {:rule-title        (get rule :rule/title "")
   :rule-description  (get rule :rule/description "")
   :knowledge-content (get rule :rule/knowledge-content "")
   :file-path         file-path
   :file-content      file-content})

(defn build-judge-prompt
  "Build system + user prompts for LLM-as-judge analysis.
   Returns {:system string :user string}."
  [rule file-path file-content]
  (let [templates @judge-templates
        bindings  (rule->prompt-bindings rule file-path file-content)]
    {:system (pt/interpolate (get templates :system) bindings)
     :user   (pt/interpolate (get templates :user) bindings)}))

;------------------------------------------------------------------------------ Layer 1
;; LLM invocation and parsing

(defn- strip-code-fences
  "Remove markdown code fences from a string."
  [s]
  (cond-> s
    (str/starts-with? s "```") (str/replace #"^```\w*\n?" "")
    (str/ends-with? s "```")   (str/replace #"\n?```$" "")))

(defn- parse-judge-response
  "Parse the LLM's EDN response into violation maps.
   Returns a vector of violations, or empty vector on parse failure."
  [response-text]
  (try
    (let [cleaned (-> (str response-text) str/trim strip-code-fences str/trim)
          parsed  (edn/read-string cleaned)]
      (if (vector? parsed)
        (filterv map? parsed)
        []))
    (catch Exception _ [])))

(defn- raw->violation
  "Convert a single raw LLM violation to canonical shape via factory."
  [rule file-path v]
  (factory/->violation
   (get rule :rule/id)
   (get rule :rule/category "000")
   (get rule :rule/title)
   file-path
   (get v :line 0)
   (get v :current "")
   nil
   false
   (get v :message "Semantic analysis violation")))

(defn- judge-response->violations
  "Convert parsed judge response to canonical violation maps."
  [rule file-path raw-violations]
  (mapv #(raw->violation rule file-path %) raw-violations))

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
  "Maximum number of files to analyze per rule.
   Kept low to stay within per-rule timeout when using CLI backends."
  5)

;; NOTE: This mirrors compliance-scanner.scan/globs->file-pred.
;; Glob matching should be extracted to repo-index component.
(defn- glob-matcher
  "Create a PathMatcher for a glob pattern."
  [glob]
  (.getPathMatcher (java.nio.file.FileSystems/getDefault) (str "glob:" glob)))

(defn- file-matches-globs?
  "Check if a file path matches any of the rule's file globs."
  [file-path globs]
  (let [path (java.nio.file.Paths/get file-path (into-array String []))]
    (some #(.matches (glob-matcher %) path) globs)))

(defn- under-size-limit?
  "True when a file is under the max size limit."
  [f]
  (< (.length f) max-file-size-bytes))

(defn- relativize
  "Get the relative path of a file from a root directory."
  [repo-path f]
  (.toString (.relativize (.toPath (io/file repo-path)) (.toPath f))))

(defn select-files-for-rule
  "Select files from repo that match a rule's file globs.
   Limits to max-files-per-rule, skips files over max-file-size-bytes."
  [repo-path rule]
  (let [globs (get-in rule [:rule/applies-to :file-globs] ["**/*"])]
    (->> (file-seq (io/file repo-path))
         (filter #(.isFile %))
         (filter under-size-limit?)
         (filter #(file-matches-globs? (relativize repo-path %) globs))
         (take max-files-per-rule))))

(defn- analyze-file-in-repo
  "Analyze a single file from the repo against a rule."
  [llm-client complete-fn repo-path rule f]
  (let [rel-path (relativize repo-path f)
        content  (slurp f)]
    (analyze-file llm-client complete-fn rule rel-path content)))

(def ^:private default-rule-timeout-ms
  "Maximum time to spend analyzing one rule before giving up.
   5 files × ~30s per CLI backend call = ~150s, so 300s gives headroom."
  300000)

(defn analyze-rule
  "Analyze all matching files against a single behavioral rule.

   Arguments:
   - llm-client — LLM client
   - complete-fn — LLM completion function
   - repo-path — Repository root path
   - rule — Behavioral rule with :rule/knowledge-content

   Returns:
   - {:rule/id keyword :violations [...] :files-analyzed int :duration-ms int :status keyword}"
  [llm-client complete-fn repo-path rule]
  (let [start-ms (System/currentTimeMillis)
        files    (select-files-for-rule repo-path rule)
        viols    (vec (mapcat #(analyze-file-in-repo llm-client complete-fn repo-path rule %)
                              files))
        end-ms   (System/currentTimeMillis)]
    {:rule/id        (get rule :rule/id)
     :violations     viols
     :files-analyzed (count files)
     :duration-ms    (- end-ms start-ms)
     :status         :completed}))

(defn- analyze-rule-with-timeout
  "Run analyze-rule with a timeout. Returns result or timeout marker."
  [llm-client complete-fn repo-path rule timeout-ms]
  (let [fut (future (analyze-rule llm-client complete-fn repo-path rule))]
    (try
      (let [result (deref fut timeout-ms ::timeout)]
        (if (= result ::timeout)
          (do (future-cancel fut)
              {:rule/id      (get rule :rule/id)
               :violations   []
               :files-analyzed 0
               :duration-ms  timeout-ms
               :status       :timeout})
          result))
      (catch Exception e
        {:rule/id      (get rule :rule/id)
         :violations   []
         :files-analyzed 0
         :duration-ms  0
         :status       :error
         :error        (.getMessage e)}))))

(defn analyze-rules-parallel
  "Analyze all behavioral rules in parallel with per-rule timeouts.

   Arguments:
   - llm-client — LLM client
   - complete-fn — LLM completion function
   - repo-path — Repository root path
   - rules — Vector of behavioral rules
   - opts — Options map:
     :timeout-ms — Per-rule timeout (default 120000)
     :max-parallel — Max concurrent rules (default 4)

   Returns:
   - Vector of per-rule result maps"
  [llm-client complete-fn repo-path rules opts]
  (let [timeout-ms   (get opts :timeout-ms default-rule-timeout-ms)
        max-parallel (get opts :max-parallel 4)
        partitions   (partition-all max-parallel rules)]
    (->> partitions
         (mapcat (fn [batch]
                   (let [futures (mapv #(future (analyze-rule-with-timeout
                                                 llm-client complete-fn repo-path % timeout-ms))
                                      batch)]
                     (mapv deref futures))))
         vec)))

(defn behavioral-rules
  "Filter rules to those with :rule/knowledge-content (behavioral/semantic rules).
   These are the rules that need LLM analysis, not mechanical scanning."
  [pack-rules]
  (filterv :rule/knowledge-content pack-rules))
