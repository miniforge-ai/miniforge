(ns ai.miniforge.agent.implementer
  "Implementer agent implementation.
   Generates code from plans and task descriptions."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
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

;; System Prompt - loaded from resources/prompts/implementer.edn

(def implementer-system-prompt
  "System prompt for the implementer agent.
   Loaded from EDN resource for configurability."
  (delay (prompts/load-prompt :implementer)))

;------------------------------------------------------------------------------ Layer 1
;; Implementer functions

(defn validate-code-artifact
  "Validate a code artifact against the schema and check for issues."
  [artifact]
  (let [schema-valid? (m/validate CodeArtifact artifact)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain CodeArtifact artifact)}
      ;; Additional validations
      (let [files (:code/files artifact)
            empty-creates (filter (fn [f]
                                    (and (= :create (:action f))
                                         (empty? (:content f))))
                                  files)
            duplicate-paths (let [paths (map :path files)
                                  freqs (frequencies paths)]
                              (filter #(> (val %) 1) freqs))]
        (cond
          (seq empty-creates)
          {:valid? false
           :errors {:files (str "Empty content for :create files: "
                                (mapv :path empty-creates))}}

          (seq duplicate-paths)
          {:valid? false
           :errors {:files (str "Duplicate file paths: " (keys duplicate-paths))}}

          :else
          {:valid? true :errors nil})))))

(defn- extract-language
  "Extract the programming language from file paths or context."
  [files context]
  (let [extensions (->> files
                        (map :path)
                        (map #(last (re-find #"\.(\w+)$" %)))
                        (remove nil?)
                        frequencies)
        most-common (when (seq extensions)
                      (key (apply max-key val extensions)))]
    (or (:language context)
        (case most-common
          "clj" "clojure"
          "cljs" "clojurescript"
          "cljc" "clojure"
          "py" "python"
          "js" "javascript"
          "ts" "typescript"
          "java" "java"
          "go" "go"
          "rs" "rust"
          most-common))))

(defn- format-existing-files
  "Format existing file contents for inclusion in the user prompt.

   Arguments:
   - files - Vector of {:path :content :truncated? :lines} maps

   Returns:
   - Formatted markdown string, or nil if no files"
  [files]
  (when (seq files)
    (str "\n\n## Existing Files in Scope\n\n"
         "The following files already exist. Review them before generating code.\n"
         (->> files
              (map (fn [{:keys [path content truncated?]}]
                     (str "\n### " path
                          (when truncated? " (truncated)")
                          "\n```\n" content "\n```")))
              (str/join "\n")))))

(defn- task->text
  "Convert a task to text for the LLM.
   Includes plan and intent when available for richer context."
  [task]
  (cond
    (string? task) task
    (map? task) (let [desc (or (:task/description task)
                               (:description task)
                               (:content task))
                      plan (:task/plan task)
                      intent (:task/intent task)
                      parts (cond-> []
                              desc (conj desc)
                              plan (conj (str "\n\n## Plan\n\n" (if (string? plan) plan (pr-str plan))))
                              (and intent (map? intent))
                              (conj (str "\n\n## Intent\n\n" (pr-str intent))))]
                  (if (seq parts)
                    (str/join "" parts)
                    (pr-str task)))
    :else (str task)))

(defn- parse-code-response
  "Parse the LLM response to extract code artifact.
   Handles both EDN in code blocks and plain EDN.
   Returns nil if the parsed result is not a map."
  [response-content]
  (try
    (let [parsed (if-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" response-content)]
                   (edn/read-string (second match))
                   ;; Try to parse the whole response as EDN
                   (edn/read-string response-content))]
      ;; Validate that the parsed result is a map (code artifact should be a map)
      (when (map? parsed)
        parsed))
    (catch Exception _
      nil)))

(defn- extract-code-blocks
  "Extract code blocks from markdown response and convert to file list."
  [response-content]
  (let [blocks (re-seq #"```(?:(\w+)\n)?([^`]+)```" response-content)]
    (when (seq blocks)
      (->> blocks
           (map-indexed (fn [idx [_full lang content]]
                          {:path (str "generated/file" idx
                                      (case lang
                                        "clojure" ".clj"
                                        "clj" ".clj"
                                        "python" ".py"
                                        "javascript" ".js"
                                        "typescript" ".ts"
                                        ".clj"))
                           :content (str/trim content)
                           :action :create}))
           vec))))

;; make-fallback-code removed — silent fallback masks real failures,
;; prevents retry/repair from working, and short-circuits checkpoint resume.

(defn- repair-code-artifact
  "Attempt to repair a code artifact based on validation errors."
  [artifact errors _context]
  (let [repaired (atom artifact)]
    ;; Fix missing ID
    (when-not (:code/id @repaired)
      (swap! repaired assoc :code/id (random-uuid)))

    ;; Fix missing files
    (when-not (:code/files @repaired)
      (swap! repaired assoc :code/files []))

    ;; Fix files without required fields
    (swap! repaired update :code/files
           (fn [files]
             (mapv (fn [f]
                     (cond-> f
                       (not (:path f))
                       (assoc :path "src/generated/unknown.clj")

                       (nil? (:content f))
                       (assoc :content "")

                       (not (:action f))
                       (assoc :action :create)))
                   files)))

    ;; Add content to empty :create files
    (swap! repaired update :code/files
           (fn [files]
             (mapv (fn [f]
                     (if (and (= :create (:action f))
                              (empty? (:content f)))
                       (assoc f :content ";; TODO: Implement\n")
                       f))
                   files)))

    ;; Deduplicate file paths (keep last occurrence)
    (let [seen (atom #{})
          deduped (reduce (fn [acc f]
                            (if (contains? @seen (:path f))
                              acc
                              (do (swap! seen conj (:path f))
                                  (conj acc f))))
                          []
                          (reverse (:code/files @repaired)))]
      (swap! repaired assoc :code/files (vec (reverse deduped))))

    {:status :success
     :output @repaired
     :repairs-made (when (not= artifact @repaired)
                     {:original-errors errors})}))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn create-implementer
  "Create an Implementer agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)

   Example:
     (create-implementer)
     (create-implementer {:config {:model \"claude-sonnet-4\" :temperature 0.2}})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))]
    (specialized/create-base-agent
     {:role :implementer
      :system-prompt @implementer-system-prompt
      :config (merge {:model "claude-sonnet-4"
                      :temperature 0.2
                      :max-tokens 8000}
                     (:config opts))
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (or (:llm-backend opts) (:llm-backend context))
              on-chunk (:on-chunk context)
              task-text (task->text input)
              ;; Append behavior addendum to system prompt if present
              effective-system-prompt (str @implementer-system-prompt
                                          (get input :task/behavior-addendum ""))
              ;; Include existing files and already-implemented escape hatch
              user-prompt (str "Implement the following task:\n\n"
                               task-text
                               (format-existing-files (:task/existing-files input))
                               "\n\nOutput your code as a Clojure map following the format in your system prompt. "
                               "Include full file contents, not placeholders."
                               "\n\nIf the task is already fully implemented in the existing files, respond with:\n"
                               "```clojure\n{:status :already-implemented\n"
                               " :summary \"Brief explanation of why no changes are needed\"}\n```")]
          (if llm-client
            ;; Use the real LLM with artifact session for MCP tool support
            (let [{:keys [llm-result artifact]}
                  (artifact-session/with-artifact-session [session]
                    (let [mcp-opts {:mcp-config (:mcp-config-path session)
                                    :mcp-allowed-tools (:mcp-allowed-tools session)
                                    :supervision (:supervision session)}]
                      (if on-chunk
                        (llm/chat-stream llm-client user-prompt on-chunk
                                         (merge {:system effective-system-prompt} mcp-opts))
                        (llm/chat llm-client user-prompt
                                  (merge {:system effective-system-prompt} mcp-opts)))))
                  response llm-result
                  tokens (get response :tokens 0)]
              (log/info logger :implementer :implementer/llm-called
                        {:data {:success (llm/success? response)
                                :tokens tokens
                                :streaming? (boolean on-chunk)
                                :mcp-artifact? (boolean artifact)}})
              (if (llm/success? response)
                ;; Check for MCP artifact first, then fall back to text parsing
                (let [content (llm/get-content response)
                      parsed (or artifact (parse-code-response content))]
                  (when artifact
                    (log/info logger :implementer :implementer/mcp-artifact-received
                              {:data {:file-count (count (:code/files artifact))}}))
                  ;; Check for already-implemented response
                  (if (= :already-implemented (:status parsed))
                    {:status :already-implemented
                     :output {:code/id (random-uuid)
                              :code/files []
                              :code/summary (:summary parsed)
                              :code/language nil
                              :code/tests-needed? false
                              :code/created-at (java.util.Date.)}
                     :summary (:summary parsed)
                     :metrics {:tokens tokens
                               :files-created 0
                               :skipped-reason :already-implemented}}
                    ;; Normal code artifact parsing
                    (if-let [code (or parsed
                                      (when-let [files (extract-code-blocks content)]
                                        {:code/id (random-uuid)
                                         :code/files files
                                         :code/tests-needed? true
                                         :code/summary "Implementation from code blocks"
                                         :code/created-at (java.util.Date.)}))]
                      (let [code-with-meta (-> code
                                               (update :code/id #(or % (random-uuid)))
                                               (assoc :code/language (extract-language (:code/files code) context))
                                               (assoc :code/created-at (java.util.Date.)))
                            lang (:code/language code-with-meta)]
                        (response/success code-with-meta
                                          {:tokens tokens
                                           :metrics {:files-created (count (filter #(= :create (:action %)) (:code/files code-with-meta)))
                                                     :files-modified (count (filter #(= :modify (:action %)) (:code/files code-with-meta)))
                                                     :files-deleted (count (filter #(= :delete (:action %)) (:code/files code-with-meta)))
                                                     :language lang
                                                     :tokens tokens}}))
                      ;; LLM returned content but no parseable code — fail explicitly
                      (response/error "LLM response could not be parsed as code artifact"
                                      {:tokens tokens}))))
                ;; LLM call failed — propagate error, no fallback
                (response/error (or (:message (llm/get-error response))
                                    "LLM call failed"))))
            ;; No LLM client — fail explicitly
            (response/error "No LLM backend provided"))))

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
       (filter #(not= :delete (:action %)))
       (map :content)
       (map #(count (clojure.string/split-lines %)))
       (reduce + 0)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create an implementer
  (def impl (create-implementer))

  ;; Invoke with a task
  (specialized/invoke impl
               {:language "clojure"
                :suggested-path "src/ai/miniforge/auth/login.clj"}
               {:task/description "Implement user login with email verification"
                :task/type :implement})
  ;; => {:status :success, :output {:code/id ..., :code/files [...], ...}}

  ;; Validate a code artifact
  (validate-code-artifact
   {:code/id (random-uuid)
    :code/files [{:path "src/example.clj"
                  :content "(ns example)"
                  :action :create}]})
  ;; => {:valid? true, :errors nil}

  ;; Get summary
  (code-summary {:code/id (random-uuid)
                 :code/files [{:path "a.clj" :content "" :action :create}
                              {:path "b.clj" :content "" :action :modify}]
                 :code/language "clojure"})

  :leave-this-here)
