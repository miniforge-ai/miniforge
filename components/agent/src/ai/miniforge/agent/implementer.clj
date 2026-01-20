(ns ai.miniforge.agent.implementer
  "Implementer agent implementation.
   Generates code from plans and task descriptions."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [clojure.string :as str]
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

;; System Prompt

(def implementer-system-prompt
  "You are an Implementation Agent for an autonomous software development team.

Your role is to generate high-quality code that implements specified tasks from a plan.
You work alongside Planner, Tester, and Reviewer agents.

## Input Format

You receive:
1. A task specification with:
   - Description of what to implement
   - Acceptance criteria to satisfy
   - Task type (:implement, :configure, etc.)

2. Context including:
   - Existing code files that may need modification
   - Project conventions and style guides
   - Technology stack information
   - Constraints (language version, dependencies allowed, etc.)

## Output Format

You must output a structured code artifact as a Clojure map:

```clojure
{:code/id <uuid>
 :code/files [{:path \"src/namespace/file.clj\"
               :content \"(ns namespace.file...)\"
               :action :create} ; or :modify, :delete
              ...]
 :code/dependencies-added [\"library/name {:mvn/version \\\"1.0.0\\\"}\"]
 :code/tests-needed? true
 :code/language \"clojure\"
 :code/summary \"Brief description of changes made\"}
```

## Guidelines

1. **Code Quality**:
   - Write clean, idiomatic code for the target language
   - Follow project conventions exactly
   - Add comprehensive docstrings and comments where appropriate
   - Handle errors gracefully with meaningful messages

2. **File Actions**:
   - :create - New file, provide complete content
   - :modify - Existing file, provide complete updated content
   - :delete - File to remove, content can be empty

3. **Namespace/Module Structure**:
   - Respect existing project structure
   - Use consistent naming conventions
   - Organize imports/requires logically

4. **Dependencies**:
   - Only add dependencies when truly necessary
   - Use well-maintained, widely-used libraries
   - Specify exact versions
   - Document why the dependency is needed

5. **Testing Awareness**:
   - Set :code/tests-needed? to true if the code would benefit from tests
   - Consider testability in your implementation
   - Expose interfaces that are easy to test

6. **For Clojure Specifically**:
   - Use descriptive names with clear namespacing
   - Prefer pure functions over stateful operations
   - Use protocols and records for polymorphism
   - Add rich comment blocks for development examples

7. **Error Handling**:
   - Use ex-info with descriptive messages and data maps
   - Handle expected failure modes gracefully
   - Log appropriately (debug for details, info for events, warn/error for issues)

8. **Performance Considerations**:
   - Prefer lazy sequences for large data processing
   - Use transducers when transforming collections
   - Consider memory implications of data structures

Remember: Your code will be validated, tested, and reviewed by other agents.
Write code that is easy to understand, test, and maintain.")

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

(defn- generate-code
  "Generate code based on task and context.
   In a real implementation, this would use an LLM."
  [task context]
  (let [task-desc (or (:task/description task)
                      (:description task)
                      (str task))
        file-path (or (:suggested-path context)
                      "src/ai/miniforge/generated/impl.clj")
        namespace-name (-> file-path
                           (str/replace #"^src/" "")
                           (str/replace #"\.clj$" "")
                           (str/replace "/" ".")
                           (str/replace "_" "-"))]
    ;; Generate a stub implementation
    ;; In production, this would be LLM-generated
    {:code/id (random-uuid)
     :code/files [{:path file-path
                   :content (str "(ns " namespace-name "\n"
                                 "  \"Generated implementation.\n"
                                 "   Task: " (subs task-desc 0 (min 60 (count task-desc))) "\")\n\n"
                                 ";; TODO: Implement based on task requirements\n\n"
                                 "(defn execute\n"
                                 "  \"Main entry point for this implementation.\"\n"
                                 "  [input]\n"
                                 "  ;; Implementation goes here\n"
                                 "  {:status :not-implemented\n"
                                 "   :input input})\n")
                   :action :create}]
     :code/dependencies-added []
     :code/tests-needed? true
     :code/language "clojure"
     :code/summary (str "Initial implementation for: " (subs task-desc 0 (min 50 (count task-desc))))
     :code/created-at (java.util.Date.)}))

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

   Example:
     (create-implementer)
     (create-implementer {:config {:model \"claude-sonnet-4\" :temperature 0.2}})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))]
    (core/create-base-agent
     {:role :implementer
      :system-prompt implementer-system-prompt
      :config (merge {:model "claude-sonnet-4"
                      :temperature 0.2
                      :max-tokens 8000}
                     (:config opts))
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [code (generate-code input context)
              lang (extract-language (:code/files code) context)]
          {:status :success
           :output (assoc code :code/language lang)
           :metrics {:files-created (count (filter #(= :create (:action %)) (:code/files code)))
                     :files-modified (count (filter #(= :modify (:action %)) (:code/files code)))
                     :files-deleted (count (filter #(= :delete (:action %)) (:code/files code)))
                     :language lang}}))

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
  (core/invoke impl
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
