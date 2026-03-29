(ns ai.miniforge.agent.implementer
  "Implementer agent implementation.
   Generates code from plans and task descriptions."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.patterns.interface :as patterns]
   [ai.miniforge.repo-index.messages :as messages]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Implementer-specific schemas

(def CodeFile
  "Schema for a code file in the output."
  [:map
   [:path [:string {:min 1}]]
   [:content [:string {:min 0}]]
   [:action [:enum :create :modify :delete]]])

(def CodeArtifact
  "Schema for the implementer's output."
  [:map
   [:code/id uuid?]
   [:code/files [:vector CodeFile]]
   [:code/dependencies-added {:optional true} [:vector [:string {:min 1}]]]
   [:code/tests-needed? {:optional true} boolean?]
   [:code/language {:optional true} [:string {:min 1}]]
   [:code/summary {:optional true} [:string {:min 1}]]
   [:code/created-at {:optional true} inst?]])

(def implementer-system-prompt
  "System prompt for the implementer agent.
   Loaded from EDN resource for configurability."
  (delay (prompts/load-prompt :implementer)))

(def ^:private implementer-prompt-data
  "Full prompt data map for the implementer agent."
  (delay (prompts/load-prompt-data :implementer)))

;------------------------------------------------------------------------------ Layer 0
;; Extension → language mapping (loaded from config)

(def ^:private impl-config-path "config/repo-index/implementer.edn")

(defn- load-ext->language []
  (if-let [res (io/resource impl-config-path)]
    (get-in (edn/read-string (slurp res)) [:repo-index/implementer :extension->language] {})
    {}))

(def ^:private ext->language (delay (load-ext->language)))

;------------------------------------------------------------------------------ Layer 1
;; Validation

(defn- find-empty-creates
  "Find :create files with empty content."
  [files]
  (filter (fn [f] (and (= :create (:action f)) (empty? (:content f)))) files))

(defn- find-duplicate-paths
  "Find file paths that appear more than once."
  [files]
  (->> (map :path files)
       frequencies
       (filter #(> (val %) 1))))

(defn validate-code-artifact
  "Validate a code artifact against the schema and check for issues."
  [artifact]
  (if-not (m/validate CodeArtifact artifact)
    (schema/invalid (schema/explain CodeArtifact artifact)
                    {:errors (schema/explain CodeArtifact artifact)})
    (let [files (:code/files artifact)
          empty-creates (find-empty-creates files)
          duplicate-paths (find-duplicate-paths files)]
      (cond
        (seq empty-creates)
        (schema/invalid (str "Empty content for :create files: " (mapv :path empty-creates))
                        {:errors {:files (str "Empty content for :create files: "
                                              (mapv :path empty-creates))}})
        (seq duplicate-paths)
        (schema/invalid (str "Duplicate file paths: " (keys duplicate-paths))
                        {:errors {:files (str "Duplicate file paths: " (keys duplicate-paths))}})
        :else
        (schema/valid)))))

;------------------------------------------------------------------------------ Layer 1
;; Language detection

(defn- extract-extension
  "Extract file extension from a path."
  [path]
  (last (re-find #"\.(\w+)$" path)))

(defn extract-language
  "Extract the programming language from file paths or context."
  [files context]
  (let [extensions (->> files
                        (map :path)
                        (keep extract-extension)
                        frequencies)
        most-common (when (seq extensions)
                      (key (apply max-key val extensions)))]
    (or (:language context)
        (get @ext->language most-common most-common))))

;------------------------------------------------------------------------------ Layer 1
;; Prompt formatting

(defn format-repo-map
  "Format a repo map for inclusion in the user prompt."
  [repo-map-text]
  (when (and repo-map-text (not (str/blank? repo-map-text)))
    (str "\n\n## " (messages/t :prompt/repo-map-section-title) "\n\n"
         (messages/t :prompt/repo-map-header)
         "\n\n" repo-map-text)))

(defn- format-file-block
  "Format a single file as a markdown code block."
  [{:keys [path content truncated?]}]
  (str "\n### " path
       (when truncated? " (truncated)")
       "\n```\n" content "\n```"))

(defn format-existing-files
  "Format existing file contents for inclusion in the user prompt."
  [files]
  (when (seq files)
    (str "\n\n## " (messages/t :prompt/existing-files-section-title) "\n\n"
         (messages/t :prompt/existing-files-header) "\n"
         (->> files (map format-file-block) (str/join "\n")))))

;------------------------------------------------------------------------------ Layer 1
;; Task → text conversion

(defn- format-plan-section
  "Format the plan as a markdown section."
  [plan]
  (str "\n\n## " (messages/t :prompt/plan-header) "\n\n"
       (if (string? plan) plan (pr-str plan))))

(defn- format-intent-section
  "Format the intent as a markdown section."
  [intent]
  (str "\n\n## " (messages/t :prompt/intent-header) "\n\n"
       (pr-str intent)))

(defn- format-review-section
  "Format review feedback as a markdown section."
  [review-feedback]
  (str "\n\n## " (messages/t :prompt/review-feedback-header) "\n\n"
       (messages/t :prompt/review-feedback-intro) "\n\n"
       (if (string? review-feedback) review-feedback (pr-str review-feedback))))

(defn- format-verify-section
  "Format verify failures as a markdown section."
  [verify-failures]
  (str "\n\n## " (messages/t :prompt/test-failures-header) "\n\n"
       (messages/t :prompt/test-failures-intro) "\n\n"
       (if-let [test-results (:test-results verify-failures)]
         (str (messages/t :prompt/test-results-label) "\n" (pr-str test-results))
         (str (messages/t :prompt/verify-details-label) "\n" (pr-str verify-failures)))
       (when-let [test-output (:test-output verify-failures)]
         (str "\n\n" (messages/t :prompt/test-output-label) "\n" test-output))))

(defn task->text
  "Convert a task to text for the LLM.
   Includes plan and intent when available for richer context."
  [task]
  (cond
    (string? task) task
    (map? task) (let [desc (or (:task/description task) (:description task) (:content task))
                      plan (:task/plan task)
                      intent (:task/intent task)
                      review-feedback (:task/review-feedback task)
                      verify-failures (:task/verify-failures task)
                      parts (cond-> []
                              desc
                              (conj desc)
                              plan
                              (conj (format-plan-section plan))
                              (and intent (map? intent))
                              (conj (format-intent-section intent))
                              review-feedback
                              (conj (format-review-section review-feedback))
                              verify-failures
                              (conj (format-verify-section verify-failures)))]
                  (if (seq parts)
                    (str/join "" parts)
                    (pr-str task)))
    :else (str task)))

;------------------------------------------------------------------------------ Layer 1
;; Response parsing

(defn- try-parse-edn-block
  "Try to extract an EDN map from a fenced code block."
  [text]
  (when-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" text)]
    (let [parsed (edn/read-string (second match))]
      (when (map? parsed) parsed))))

(defn- try-parse-raw-edn
  "Try to parse the entire text as EDN, returning a map or nil."
  [text]
  (try
    (let [r (edn/read-string text)]
      (when (map? r) r))
    (catch Exception _ nil)))

(defn- try-parse-inline-status
  "Scan for an inline {:status :already-implemented ...} EDN map."
  [text]
  (when-let [match (re-find #"\{:status\s+:already-implemented[^}]*\}" text)]
    (edn/read-string match)))

(defn parse-code-response
  "Parse the LLM response to extract code artifact.
   Handles EDN in code blocks, plain EDN, and inline EDN maps.
   Returns nil if no parseable map is found."
  [response-content]
  (try
    (when-let [parsed (or (try-parse-edn-block response-content)
                          (try-parse-raw-edn response-content)
                          (try-parse-inline-status response-content))]
      (when (map? parsed) parsed))
    (catch Exception _
      nil)))

(defn- extract-path-from-context
  "Try to extract a file path from text immediately preceding a code block.
   Looks for common patterns like '### path/to/file.clj' or '**path/to/file.clj**'
   or 'File: path/to/file.clj' in the preceding lines."
  [text block-start]
  (let [preceding (subs text 0 block-start)
        ;; Take last 3 lines before the code block
        lines (take-last 3 (str/split-lines preceding))]
    (some (fn [line]
            (or
             (second (re-find patterns/md-heading-file-path line))
             (second (re-find patterns/md-delimited-file-path line))
             (second (re-find patterns/md-label-file-path line))))
          lines)))

(defn extract-code-blocks
  "Extract code blocks from markdown response and convert to file list.
   Tries to detect file paths from markdown headings before each block."
  [response-content]
  (let [pattern patterns/md-code-block
        matcher (re-matcher pattern response-content)]
    (loop [results [] idx 0]
      (if (.find matcher)
        (let [lang (.group matcher 1)
              content (.group matcher 2)
              block-start (.start matcher)
              path (or (extract-path-from-context response-content block-start)
                       (str "generated/file" idx
                            (get @ext->language lang ".clj")))]
          (recur (conj results {:path path
                                :content (str/trim content)
                                :action :create})
                 (inc idx)))
        (when (seq results) results)))))

;------------------------------------------------------------------------------ Layer 1
;; Artifact repair

(defn- ensure-required-fields
  "Ensure a file entry has content and action. Drops entries with no path."
  [f]
  (when (:path f)
    (cond-> f
      (nil? (:content f)) (assoc :content "")
      (not (:action f))   (assoc :action :create))))

(defn- fill-empty-creates
  "Add placeholder content to empty :create files."
  [f]
  (if (and (= :create (:action f)) (empty? (:content f)))
    (assoc f :content ";; TODO: Implement\n")
    f))

(defn- deduplicate-files
  "Deduplicate file entries by path, keeping last occurrence."
  [files]
  (let [seen (atom #{})
        deduped (reduce (fn [acc f]
                          (if (contains? @seen (:path f))
                            acc
                            (do (swap! seen conj (:path f))
                                (conj acc f))))
                        []
                        (reverse files))]
    (vec (reverse deduped))))

(defn repair-code-artifact
  "Attempt to repair a code artifact based on validation errors."
  [artifact errors _context]
  (let [repaired (-> artifact
                     (update :code/id #(or % (random-uuid)))
                     (update :code/files #(or % []))
                     (update :code/files #(filterv some? (map ensure-required-fields %)))
                     (update :code/files #(mapv fill-empty-creates %))
                     (update :code/files deduplicate-files))]
    {:status :success
     :output repaired
     :repairs-made (when (not= artifact repaired)
                     {:original-errors errors})}))

;------------------------------------------------------------------------------ Layer 2
;; LLM invocation helpers

(defn- build-user-prompt
  "Build the user prompt for the implementer."
  [task-text input]
  (str (messages/t :prompt/implement-task-prefix) "\n\n"
       task-text
       (format-repo-map (:task/repo-map input))
       (format-existing-files (:task/existing-files input))
       "\n\n" (messages/t :prompt/output-instruction)
       "\n\n" (messages/t :prompt/already-impl-instruction) "\n"
       (messages/t :prompt/already-impl-template)))

(defn- build-effective-system-prompt
  "Append behavior addendum to the base system prompt."
  [input]
  (str @implementer-system-prompt
       (get input :task/behavior-addendum "")))

(defn- build-already-implemented-response
  "Build the response map for an already-implemented task."
  [parsed tokens cost-usd]
  {:status :already-implemented
   :output {:code/id (random-uuid)
            :code/files []
            :code/summary (:summary parsed)
            :code/language nil
            :code/tests-needed? false
            :code/created-at (java.util.Date.)}
   :summary (:summary parsed)
   :metrics {:tokens tokens
             :cost-usd cost-usd
             :files-created 0
             :skipped-reason :already-implemented}})

(defn- count-files-by-action
  "Count files matching a given action in an artifact."
  [action files]
  (count (filter #(= action (:action %)) files)))

(defn- build-code-response
  "Build the success response for a parsed code artifact."
  [code context tokens cost-usd]
  (let [code-with-meta (-> code
                           (update :code/id #(or % (random-uuid)))
                           (assoc :code/language (extract-language (:code/files code) context))
                           (assoc :code/created-at (java.util.Date.)))
        files (:code/files code-with-meta)]
    (response/success code-with-meta
                      {:tokens tokens
                       :metrics {:files-created (count-files-by-action :create files)
                                 :files-modified (count-files-by-action :modify files)
                                 :files-deleted (count-files-by-action :delete files)
                                 :language (:code/language code-with-meta)
                                 :tokens tokens
                                 :cost-usd cost-usd}})))

(defn- code-from-blocks
  "Build a code artifact map from extracted markdown code blocks."
  [content]
  (when-let [files (extract-code-blocks content)]
    {:code/id (random-uuid)
     :code/files files
     :code/tests-needed? true
     :code/summary "Implementation from code blocks"
     :code/created-at (java.util.Date.)}))

(defn- process-llm-response
  "Process a successful LLM response, returning the appropriate result."
  [response artifact context logger tokens cost-usd]
  (let [content (llm/get-content response)
        parsed (or artifact (parse-code-response content))]
    (let [tools (get response :tools-called [])]
      (if artifact
        (log/info logger :implementer :implementer/mcp-artifact-received
                  {:data {:file-count (count (:code/files artifact))
                          :tools-called tools}})
        (log/warn logger :implementer :implementer/mcp-tool-not-called
                  {:data {:content-length (count (or content ""))
                          :tools-called tools
                          :has-code-blocks? (boolean (re-find #"```" (or content "")))}})))
    (if (= :already-implemented (:status parsed))
      (build-already-implemented-response parsed tokens cost-usd)
      (if-let [code (or parsed (code-from-blocks content))]
        (build-code-response code context tokens cost-usd)
        (response/error (messages/t :error/parse-failed) {:tokens tokens})))))

(defn- invoke-with-llm
  "Invoke the implementer via the LLM backend."
  [llm-client user-prompt effective-system-prompt config context on-chunk logger
   existing-files]
  (let [{:keys [llm-result artifact context-misses]}
        (artifact-session/with-artifact-session [session]
          ;; Write context cache so MCP tools can serve from it
          (when (seq existing-files)
            (let [files-map (into {} (map (fn [f] [(:path f) (:content f)])
                                          existing-files))]
              (artifact-session/write-context-cache! session files-map)))
          (let [budget-usd (budget/resolve-cost-budget-usd :implementer config context)
                max-turns (get @implementer-prompt-data :prompt/max-turns 10)
                mcp-opts {:mcp-config (:mcp-config-path session)
                          :mcp-allowed-tools (:mcp-allowed-tools session)
                          :supervision (:supervision session)
                          :budget-usd budget-usd
                          :max-turns max-turns}]
            (if on-chunk
              (llm/chat-stream llm-client user-prompt on-chunk
                               (merge {:system effective-system-prompt} mcp-opts))
              (llm/chat llm-client user-prompt
                        (merge {:system effective-system-prompt} mcp-opts)))))
        response llm-result
        tokens (get response :tokens 0)
        cost-usd (get response :cost-usd)]
    (when (seq context-misses)
      (log/info logger :implementer :implementer/context-cache-misses
                {:data {:miss-count (count context-misses)
                        :misses context-misses}}))
    (log/info logger :implementer :implementer/llm-called
              {:data {:success (llm/success? response)
                      :tokens tokens
                      :streaming? (boolean on-chunk)
                      :mcp-artifact? (boolean artifact)
                      :tools-called (get response :tools-called [])}})
    (if (llm/success? response)
      (process-llm-response response artifact context logger tokens cost-usd)
      (response/error (or (:message (llm/get-error response))
                          (messages/t :error/llm-failed))))))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn create-implementer
  "Create an Implementer agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))
        config (->> (merge {:temperature 0.2
                            :max-tokens 8000}
                           (:config opts))
                    (model/apply-default-model :implementer)
                    (budget/apply-default-budget :implementer))]
    (specialized/create-base-agent
     {:role :implementer
      :system-prompt @implementer-system-prompt
      :config config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :implementer
                          (or (:llm-backend opts) (:llm-backend context)))
              on-chunk (:on-chunk context)
              task-text (task->text input)
              effective-system-prompt (build-effective-system-prompt input)
              user-prompt (build-user-prompt task-text input)]
          (if llm-client
            (invoke-with-llm llm-client user-prompt effective-system-prompt
                             config context on-chunk logger
                             (:task/existing-files input))
            (response/error (messages/t :error/no-llm-backend)))))

      :validate-fn validate-code-artifact

      :repair-fn repair-code-artifact})))

(defn code-summary
  "Get a summary of a code artifact for logging/display."
  [artifact]
  {:id (:code/id artifact)
   :file-count (count (:code/files artifact))
   :actions (frequencies (map :action (:code/files artifact)))
   :language (:code/language artifact)
   :tests-needed? (:code/tests-needed? artifact)
   :dependencies-added (count (:code/dependencies-added artifact []))})

(defn files-by-action
  "Group files by their action type."
  [artifact]
  (group-by :action (:code/files artifact)))

(defn total-lines
  "Count total lines of code in the artifact."
  [artifact]
  (->> (:code/files artifact)
       (remove #(= :delete (:action %)))
       (map :content)
       (map #(count (str/split-lines %)))
       (reduce + 0)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def impl (create-implementer))

  (specialized/invoke impl
               {:language "clojure"
                :suggested-path "src/ai/miniforge/auth/login.clj"}
               {:task/description "Implement user login with email verification"
                :task/type :implement})

  (validate-code-artifact
   {:code/id (random-uuid)
    :code/files [{:path "src/example.clj"
                  :content "(ns example)"
                  :action :create}]})

  (code-summary {:code/id (random-uuid)
                 :code/files [{:path "a.clj" :content "" :action :create}
                              {:path "b.clj" :content "" :action :modify}]
                 :code/language "clojure"})

  :leave-this-here)
