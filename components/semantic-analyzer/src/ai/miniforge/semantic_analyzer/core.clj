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
   [ai.miniforge.compliance-scanner.interface :as factory]
   [ai.miniforge.policy-pack.interface :as pt]
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

(defn- load-limits
  "Read the limits EDN, returning {} if the resource is absent,
   unreadable, malformed, or not a map — so the call-site literal
   defaults remain authoritative (fail-open, matching the prior
   inline-literal behavior)."
  [path]
  (try
    (let [url    (io/resource path)
          parsed (when url (edn/read-string (slurp url)))]
      (if (map? parsed) parsed {}))
    (catch Exception _ {})))

(def ^:private limits (load-limits "config/semantic-analyzer/limits.edn"))

(def ^:private max-file-size-bytes
  "Maximum file size to send to LLM. Files larger than this are skipped."
  (get limits :max-file-size-bytes 50000))

(def ^:private max-files-per-rule
  "Maximum number of files to analyze per rule.
   Kept low to stay within per-rule timeout when using CLI backends."
  (get limits :max-files-per-rule 5))

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
  (get limits :default-rule-timeout-ms 300000))

(defn analyze-rule
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

(defn- file-under-size-limit?
  "True when an in-memory file's content is under the byte size limit. Uses `<`
   to match the repo-scan path's `under-size-limit?` (a file exactly at the
   limit is excluded by both)."
  [content]
  (< (count (.getBytes ^String content "UTF-8")) max-file-size-bytes))

(defn analyze-rule-on-files
  "Analyze an explicit set of changed files against one rule, instead of
   scanning the whole repo. `files` is a vector of {:path :content}. Filters to
   files matching the rule's globs and under the size limit, runs the judge per
   file, and returns the same result shape as `analyze-rule`.

   This is the changed-files scope: a run gates only the files it touched, and
   an in-scope file is judged in full (every violation in it must be fixed)."
  [llm-client complete-fn rule files]
  (let [start-ms (System/currentTimeMillis)
        globs    (get-in rule [:rule/applies-to :file-globs] ["**/*"])
        eligible (vec (->> files
                           (filter (fn [{:keys [path content]}]
                                     (and (string? path) (string? content)
                                          (file-under-size-limit? content)
                                          (file-matches-globs? path globs))))
                           (take max-files-per-rule)))
        viols    (vec (mapcat (fn [{:keys [path content]}]
                                (analyze-file llm-client complete-fn rule path content))
                              eligible))
        end-ms   (System/currentTimeMillis)]
    {:rule/id        (get rule :rule/id)
     :violations     viols
     :files-analyzed (count eligible)
     :duration-ms    (- end-ms start-ms)
     :status         :completed}))

;------------------------------------------------------------------------------ Layer 2b
;; Batched judge — one LLM call per file across many rules

(def ^:private batched-system-prompt
  (str "You are a code reviewer enforcing engineering standards. Analyze ONE "
       "source file against a list of rules, each rendered as a `### <rule-id>` "
       "heading. Return ONLY a valid EDN vector of violation maps, no prose or "
       "markdown. Each map: {:rule-id <the rule-id keyword> :line <int> :current "
       "<snippet> :message <why>}. Tag every violation with the :rule-id of the "
       "rule it violates. Return [] if the file complies with all rules."))

(defn- batched-rules-block
  [rules]
  (str/join "\n\n"
            (map (fn [r] (str "### " (:rule/id r) " — " (get r :rule/title "") "\n"
                              (get r :rule/description "") "\n"
                              (get r :rule/knowledge-content "")))
                 rules)))

(defn- build-batched-prompt
  [rules file-path file-content]
  {:system batched-system-prompt
   :user   (str "## File: " file-path "\n\n```\n" file-content "\n```\n\n## Rules\n\n"
                (batched-rules-block rules)
                "\n\nReturn an EDN vector of violations across ALL rules (tag each "
                ":rule-id), or [].")})

(defn- ->rule-kw
  "Coerce a model-emitted :rule-id (keyword, \":std/foo\" or \"std/foo\" string)
   to a keyword; nil for anything else."
  [x]
  (cond (keyword? x) x
        (string? x)  (keyword (str/replace x #"^:" ""))
        :else        nil))

(defn- analyze-file-batched
  "One judge call: a single file against MANY rules. Returns canonical violation
   maps, each attributed to the rule the model tagged it with. Violations tagged
   with a rule-id not in `rules` are dropped (no hallucinated rules)."
  [llm-client complete-fn rules file-path file-content]
  (let [by-id    (into {} (map (juxt :rule/id identity)) rules)
        {:keys [system user]} (build-batched-prompt rules file-path file-content)
        response (complete-fn llm-client {:system system
                                          :messages [{:role "user" :content user}]})
        raws     (parse-judge-response (get response :content ""))]
    (->> raws
         (keep (fn [v]
                 ;; Attribute by the model's tag; if a violation is untagged and
                 ;; there is exactly one rule in the batch, attribute it to that
                 ;; rule (an unambiguous single-rule batch). Otherwise drop it —
                 ;; an untagged violation across many rules can't be placed.
                 (let [rule (or (get by-id (->rule-kw (:rule-id v)))
                                (when (= 1 (count rules)) (first rules)))]
                   (when rule (raw->violation rule file-path v)))))
         vec)))

(defn analyze-rules-on-files
  "Batched changed-files judge: ONE LLM call per file, covering all `rules` whose
   globs match that file (and that are under the size limit). `files` is a vector
   of {:path :content}, `rules` a vector of rule maps. Returns
   {:by-rule {rule-id [violation...]} :files-analyzed int :calls int
    :duration-ms int :status :completed}.

   This collapses the per-rule-per-file judge (analyze-rule-on-files run once per
   rule) into one call per file: the rule pack is sent once per file, not once
   per (rule, file)."
  [llm-client complete-fn rules files]
  (let [start-ms (System/currentTimeMillis)]
    (loop [fs (seq files), by-rule {}, analyzed 0, calls 0]
      (if-let [{:keys [path content]} (first fs)]
        (let [applicable (filterv #(file-matches-globs?
                                    path (get-in % [:rule/applies-to :file-globs] ["**/*"]))
                                  rules)]
          (if (and (seq applicable) (string? path) (string? content)
                   (file-under-size-limit? content))
            (let [viols   (analyze-file-batched llm-client complete-fn applicable path content)
                  by-rule (reduce (fn [m v] (update m (:rule/id v) (fnil conj []) v))
                                  by-rule viols)]
              (recur (next fs) by-rule (inc analyzed) (inc calls)))
            (recur (next fs) by-rule analyzed calls)))
        {:by-rule        by-rule
         :files-analyzed analyzed
         :calls          calls
         :duration-ms    (- (System/currentTimeMillis) start-ms)
         :status         :completed}))))

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
