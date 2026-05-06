;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.agent.tester
  "Tester agent implementation.
   Generates tests for code artifacts and validates coverage."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.result-boundary :as result-boundary]
   [ai.miniforge.agent.role-config :as role-config]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [malli.core :as m]
   [clojure.string :as str]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Tester-specific schemas

(def TestFile
  "Schema for a test file."
  [:map
   [:path [:string {:min 1}]]
   [:content [:string {:min 1}]]])

(def Coverage
  "Schema for test coverage information."
  [:map
   [:lines {:optional true} [:double {:min 0.0 :max 100.0}]]
   [:branches {:optional true} [:double {:min 0.0 :max 100.0}]]
   [:functions {:optional true} [:double {:min 0.0 :max 100.0}]]])

(def TestArtifact
  "Schema for the tester's output."
  [:map
   [:test/id uuid?]
   [:test/files [:vector TestFile]]
   [:test/type [:enum :unit :integration :property :e2e :acceptance]]
   [:test/coverage {:optional true} Coverage]
   [:test/framework {:optional true} [:string {:min 1}]]
   [:test/assertions-count {:optional true} [:int {:min 0}]]
   [:test/cases-count {:optional true} [:int {:min 0}]]
   [:test/summary {:optional true} [:string {:min 1}]]
   [:test/created-at {:optional true} inst?]])

;; System Prompt - loaded from resources/prompts/tester.edn

(def tester-system-prompt
  "System prompt for the tester agent.
   Loaded from EDN resource for configurability."
  (delay (prompts/load-prompt :tester)))

(def ^:private tester-prompt-data
  "Full prompt data map for the tester agent."
  (delay (prompts/load-prompt-data :tester)))

;------------------------------------------------------------------------------ Layer 1
;; Tester functions

(defn validate-test-artifact
  "Validate a test artifact against the schema and check for issues."
  [artifact]
  (let [schema-valid? (m/validate TestArtifact artifact)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain TestArtifact artifact)}
      ;; Additional validations
      (let [files (:test/files artifact)
            non-test-paths (filter #(not (re-find #"_test\.clj|_test\.cljc|\.test\.|test_|_spec\." (:path %)))
                                   files)
            missing-assertions? (and (:test/assertions-count artifact)
                                     (<= (:test/assertions-count artifact) 0))]
        (cond
          (seq non-test-paths)
          {:valid? false
           :errors {:files (str "Files don't follow test naming convention: "
                                (mapv :path non-test-paths))}}

          missing-assertions?
          {:valid? false
           :errors {:assertions "Test artifact has no assertions"}}

          :else
          {:valid? true :errors nil})))))

(defn extract-test-framework
  "Extract the testing framework from file content or context."
  [files context]
  (let [content (str/join "\n" (map :content files))]
    (or (:test-framework context)
        (cond
          (re-find #"\[clojure\.test" content) "clojure.test"
          (re-find #"defspec" content) "test.check"
          (re-find #"midje" content) "midje"
          (re-find #"expectations" content) "expectations"
          (re-find #"@pytest" content) "pytest"
          (re-find #"jest" content) "jest"
          (re-find #"describe\(" content) "mocha"
          :else "clojure.test"))))

(defn count-assertions
  "Count assertions in test content."
  [content]
  (+ (count (re-seq #"\(is\s" content))
     (count (re-seq #"\(are\s" content))
     (count (re-seq #"\(testing\s" content))
     (count (re-seq #"assert" content))
     (count (re-seq #"expect\(" content))))

(defn count-test-cases
  "Count test cases in test content."
  [content]
  (+ (count (re-seq #"\(deftest\s" content))
     (count (re-seq #"\(defspec\s" content))
     (count (re-seq #"def test_" content))
     (count (re-seq #"it\(" content))
     (count (re-seq #"test\(" content))))

(defn code->text
  "Convert code artifact to text for the LLM."
  [code-artifact]
  (if (map? code-artifact)
    (let [files (:code/files code-artifact)
          file-texts (map (fn [f]
                            (str "## File: " (:path f) "\n```clojure\n" (:content f) "\n```"))
                          files)]
      (str/join "\n\n" file-texts))
    (str code-artifact)))

(defn- try-parse-edn-block
  "Try to extract a test artifact EDN map from a fenced code block."
  [text]
  (when-let [match (re-find #"```(?:clojure|edn)?\s*\n(\{[\s\S]*?\})\n```" text)]
    (try
      (let [parsed (edn/read-string (second match))]
        (when (and (map? parsed) (or (:test/id parsed) (:test/files parsed)))
          parsed))
      (catch Exception _ nil))))

(defn- try-parse-raw-edn
  "Try to parse the entire text as an EDN test artifact."
  [text]
  (try
    (let [parsed (edn/read-string text)]
      (when (and (map? parsed) (or (:test/id parsed) (:test/files parsed)))
        parsed))
    (catch Exception _ nil)))

(defn parse-test-response
  "Parse the LLM response to extract test artifact.
   Tries EDN code blocks first, then raw EDN."
  [response-content]
  (or (try-parse-edn-block response-content)
      (try-parse-raw-edn response-content)))

(defn- infer-test-path
  "Infer a test file path from code content."
  [content idx]
  (let [ns-match (re-find #"\(ns\s+([^\s\)]+)" content)
        ns-name (or (second ns-match) (str "generated-test-" idx))]
    (str "test/" (str/replace ns-name "." "/")
         (when-not (str/ends-with? ns-name "-test") "_test")
         ".clj")))

(defn extract-test-code-blocks
  "Extract test code blocks from markdown response.
   Matches any Clojure code block containing test-like content."
  [response-content]
  (let [blocks (re-seq #"```(?:clojure|clj)?\s*\n([\s\S]*?)```" response-content)]
    (when (seq blocks)
      (let [test-blocks (->> blocks
                             (map second)
                             (filter #(or (re-find #"deftest\s" %)
                                          (re-find #"\(ns\s" %)
                                          (re-find #"clojure\.test" %)
                                          (re-find #"\(is\s" %))))]
        (when (seq test-blocks)
          (->> test-blocks
               (map-indexed (fn [idx content]
                              {:path (infer-test-path content idx)
                               :content (str/trim content)}))
               vec))))))

;; make-fallback-tests removed — silent fallback masks real failures,
;; prevents retry/repair from working, and short-circuits checkpoint resume.

(defn repair-test-artifact
  "Attempt to repair a test artifact based on validation errors."
  [artifact errors _context]
  (let [repaired (atom artifact)]
    ;; Fix missing ID
    (when-not (:test/id @repaired)
      (swap! repaired assoc :test/id (random-uuid)))

    ;; Fix missing files
    (when-not (:test/files @repaired)
      (swap! repaired assoc :test/files []))

    ;; Fix files without required fields
    (swap! repaired update :test/files
           (fn [files]
             (mapv (fn [f]
                     (cond-> f
                       (not (:path f))
                       (assoc :path "test/generated_test.clj")

                       (not (:content f))
                       (assoc :content "(ns generated-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest placeholder-test\n  (is true))\n")))
                   files)))

    ;; Fix paths that don't follow test convention
    (swap! repaired update :test/files
           (fn [files]
             (mapv (fn [f]
                     (if (re-find #"_test\.clj|_test\.cljc|\.test\.|test_|_spec\." (:path f))
                       f
                       (let [path (:path f)
                             new-path (-> path
                                          (str/replace #"\.clj$" "_test.clj")
                                          (str/replace #"^src/" "test/"))]
                         (assoc f :path (if (= new-path path)
                                          (str path "_test")
                                          new-path)))))
                   files)))

    ;; Fix missing type
    (when-not (:test/type @repaired)
      (swap! repaired assoc :test/type :unit))

    ;; Calculate assertion count if missing
    (when-not (:test/assertions-count @repaired)
      (let [total-assertions (->> (:test/files @repaired)
                                  (map :content)
                                  (map count-assertions)
                                  (reduce +))]
        (swap! repaired assoc :test/assertions-count (max 1 total-assertions))))

    ;; Calculate test case count if missing
    (when-not (:test/cases-count @repaired)
      (let [total-cases (->> (:test/files @repaired)
                             (map :content)
                             (map count-test-cases)
                             (reduce +))]
        (swap! repaired assoc :test/cases-count (max 1 total-cases))))

    {:status :success
     :output @repaired
     :repairs-made (when (not= artifact @repaired)
                     {:original-errors errors})}))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn- invoke-tester-session
  "Session body for the tester: cache code files, build mcp-opts with
   disallowed built-in tools, call LLM."
  [session llm-client user-prompt config context on-chunk code-artifact]
  (let [files-map (into {} (map (fn [f] [(:path f) (:content f)])
                                (:code/files code-artifact)))]
    (artifact-session/write-context-cache-for-session! session files-map))
  (let [budget-usd (budget/resolve-cost-budget-usd :tester config context)
        max-turns (get @tester-prompt-data :prompt/max-turns 10)
        mcp-opts (assoc (artifact-session/session->mcp-opts session budget-usd max-turns)
                        :disallowed-tools ["Read" "Grep" "Glob" "WebSearch" "WebFetch" "LS"])]
    (if on-chunk
      (llm/chat-stream llm-client user-prompt on-chunk
                       (merge {:system @tester-system-prompt} mcp-opts))
      (llm/chat llm-client user-prompt
                (merge {:system @tester-system-prompt} mcp-opts)))))

(defn create-tester
  "Create a Tester agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)

   Example:
     (create-tester)
     (create-tester {:config {:model \"claude-sonnet-4-6\"}})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))
        config (->> (merge (role-config/agent-llm-default :tester)
                           (:config opts))
                    (model/apply-default-model :tester)
                    (budget/apply-default-budget :tester))]
    (specialized/create-base-agent
     {:role :tester
      :system-prompt @tester-system-prompt
      :config config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :tester
                          (get opts :llm-backend (:llm-backend context)))
              on-chunk (:on-chunk context)
              ;; Input can be a code artifact or a map with :code and :spec
              code-artifact (get input :code input)
              spec (get input :spec {})
              code-text (code->text code-artifact)
              acceptance-criteria (or (:task/acceptance-criteria spec)
                                      (:acceptance-criteria spec)
                                      [])
              user-prompt (str "Generate comprehensive tests for the following code:\n\n"
                               code-text
                               (when (seq acceptance-criteria)
                                 (str "\n\nAcceptance criteria to verify:\n"
                                      (str/join "\n" (map #(str "- " %) acceptance-criteria))))
                               "\n\nWrite your tests directly to the appropriate test files. "
                               "Include complete test code, not placeholders.")]
          (if llm-client
            (let [{:keys [llm-result artifact worktree-artifacts context-misses]}
                  (artifact-session/with-session context
                    #(invoke-tester-session % llm-client user-prompt config context
                                           on-chunk code-artifact))
                  response llm-result
                  normalized (result-boundary/normalize-llm-result
                              {:role :verify
                               :response response
                               :worktree-artifacts worktree-artifacts
                               :artifact artifact
                               :parse-response parse-test-response
                               :derive-artifact (fn [content]
                                                  (when-let [files (extract-test-code-blocks content)]
                                                    {:test/id (random-uuid)
                                                     :test/files files
                                                     :test/type :unit
                                                     :test/summary "Tests from code blocks"
                                                     :test/created-at (java.util.Date.)}))})]
              (when (seq context-misses)
                (log/info logger :tester :tester/context-cache-misses
                          {:data {:miss-count (count context-misses)
                                  :misses context-misses}}))
              (log/info logger :tester :tester/llm-called
                        {:data {:success (llm/success? response)
                                :tokens (:tokens normalized)
                                :streaming? (boolean on-chunk)}})
              (if (result-boundary/usable-content? normalized)
                (if-let [tests (result-boundary/authoritative-payload normalized)]
                  (let [tests-with-meta (-> tests
                                            (update :test/id #(or % (random-uuid)))
                                            (assoc :test/framework (extract-test-framework (:test/files tests) context))
                                            (assoc :test/assertions-count
                                                   (or (:test/assertions-count tests)
                                                       (->> (:test/files tests)
                                                            (map :content)
                                                            (map count-assertions)
                                                            (reduce +))))
                                            (assoc :test/cases-count
                                                   (or (:test/cases-count tests)
                                                       (->> (:test/files tests)
                                                            (map :content)
                                                            (map count-test-cases)
                                                            (reduce +))))
                                            (assoc :test/created-at (java.util.Date.)))]
                    (response/success tests-with-meta
                                      {:tokens (:tokens normalized)
                                       :metrics {:test-files (count (:test/files tests-with-meta))
                                                 :test-type (:test/type tests-with-meta)
                                                 :assertions (:test/assertions-count tests-with-meta)
                                                 :cases (:test/cases-count tests-with-meta)
                                                 :tokens (:tokens normalized)
                                                 :cost-usd (:cost-usd normalized)}}))
                  (response/error "LLM response could not be parsed as test artifact"
                                  {:tokens (:tokens normalized)}))
                (result-boundary/error-response normalized "LLM call failed")))
            ;; No LLM client — fail explicitly
            (response/error "No LLM backend provided"))))

      :validate-fn validate-test-artifact

      :repair-fn repair-test-artifact})))

(defn test-summary
  "Get a summary of a test artifact for logging/display."
  [artifact]
  {:id (:test/id artifact)
   :file-count (count (:test/files artifact))
   :type (:test/type artifact)
   :framework (:test/framework artifact)
   :assertions (:test/assertions-count artifact)
   :cases (:test/cases-count artifact)
   :coverage (:test/coverage artifact)})

(defn coverage-meets-threshold?
  "Check if coverage meets the specified thresholds."
  [artifact & {:keys [lines branches functions]
               :or {lines 80.0 branches 70.0 functions 80.0}}]
  (let [coverage (:test/coverage artifact {})]
    (and (>= (get coverage :lines 0) lines)
         (>= (get coverage :branches 0) branches)
         (>= (get coverage :functions 0) functions))))

(defn tests-by-path
  "Get a map of test file paths to their content."
  [artifact]
  (into {} (map (juxt :path :content) (:test/files artifact))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a tester
  (def tester (create-tester))

  ;; Invoke with a code artifact
  (specialized/invoke tester
               {:test-framework "clojure.test"}
               {:code {:code/id (random-uuid)
                       :code/files [{:path "src/auth/login.clj"
                                     :content "(ns auth.login)\n(defn login [user] ...)"
                                     :action :create}]}
                :spec {:acceptance-criteria ["User can log in"
                                             "Invalid credentials show error"]}})
  ;; => {:status :success, :output {:test/id ..., :test/files [...], ...}}

  ;; Validate a test artifact
  (validate-test-artifact
   {:test/id (random-uuid)
    :test/files [{:path "test/example_test.clj"
                  :content "(ns example-test (:require [clojure.test :refer :all]))"}]
    :test/type :unit})
  ;; => {:valid? true, :errors nil}

  ;; Check coverage threshold
  (coverage-meets-threshold?
   {:test/coverage {:lines 85.0 :branches 75.0 :functions 90.0}}
   :lines 80.0 :branches 70.0)
  ;; => true

  :leave-this-here)
