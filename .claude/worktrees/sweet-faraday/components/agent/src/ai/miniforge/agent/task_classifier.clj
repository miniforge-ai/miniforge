(ns ai.miniforge.agent.task-classifier
  "Intelligent task classification for automatic model selection.
   Layer 0: Classification rules and keyword patterns
   Layer 1: Feature extraction from tasks
   Layer 2: Classification logic with confidence scoring"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Classification rules and patterns

(def task-type-keywords
  "Keywords that indicate specific task types."
  {:thinking-heavy
   #{:plan :planning :architecture :design :reasoning :strategy
     :research :analyze :evaluate :decide :decompose :adr
     :trade-off :complexity :novel :meta}

   :execution-focused
   #{:implement :code :write :refactor :edit :build :create
     :develop :generate :test :review :document :fix :debug
     :update :modify :change :add :remove}

   :simple-validation
   #{:validate :check :verify :lint :format :syntax
     :sanity :quick :simple :trivial :batch :repetitive}

   :large-context
   #{:large :codebase :repository :entire :comprehensive
     :multi-file :cross-file :global :full}

   :privacy-sensitive
   #{:private :confidential :sensitive :proprietary :offline
     :local :air-gapped :compliance :secure}

   :cost-optimized
   #{:cheap :economical :budget :cost :free :batch :volume}})

(def phase-to-task-type
  "Map workflow phases to task types."
  {:plan :thinking-heavy
   :design :thinking-heavy
   :architecture :thinking-heavy
   :research :thinking-heavy

   :implement :execution-focused
   :code :execution-focused
   :test :execution-focused
   :review :execution-focused
   :document :execution-focused

   :validate :simple-validation
   :format :simple-validation
   :lint :simple-validation
   :syntax-check :simple-validation

   :release :execution-focused
   :deploy :execution-focused})

(def agent-type-to-task-type
  "Map agent types to their primary task classification."
  {:planner-agent :thinking-heavy
   :architect-agent :thinking-heavy
   :research-agent :thinking-heavy

   :implementer-agent :execution-focused
   :executor-agent :execution-focused
   :tester-agent :execution-focused
   :reviewer-agent :execution-focused
   :documenter-agent :execution-focused

   :validator-agent :simple-validation
   :formatter-agent :simple-validation
   :linter-agent :simple-validation})

;------------------------------------------------------------------------------ Layer 1
;; Feature extraction

(defn extract-keywords
  "Extract keywords from text (description, title, etc.)."
  [text]
  (when text
    (let [normalized (-> text
                         str/lower-case
                         (str/replace #"[^a-z0-9\s-]" " "))]
      (->> (str/split normalized #"\s+")
           (map keyword)
           set))))

(defn count-keyword-matches
  "Count how many keywords from a task type appear in the text keywords."
  [text-keywords task-type-keywords]
  (count (set/intersection text-keywords task-type-keywords)))

(defn extract-context-size
  "Estimate context size from task metadata."
  [{:keys [file-count estimated-loc context-tokens]}]
  (cond
    context-tokens context-tokens
    estimated-loc estimated-loc
    file-count (* file-count 500) ; Estimate 500 tokens per file
    :else 0))

(defn extract-privacy-requirements
  "Check if task has privacy requirements."
  [{:keys [privacy-required offline-mode local-only compliance]}]
  (or privacy-required offline-mode local-only compliance))

(defn extract-cost-constraints
  "Extract cost constraints from task."
  [{:keys [cost-limit budget-constrained high-volume]}]
  (or cost-limit budget-constrained high-volume))

;------------------------------------------------------------------------------ Layer 2
;; Classification logic

(defn classify-by-phase
  "Classify task based on workflow phase."
  [phase]
  (when phase
    (let [task-type (get phase-to-task-type phase)]
      (when task-type
        {:type task-type
         :confidence 0.9
         :reason (format "Phase '%s' typically requires %s tasks" phase task-type)}))))

(defn classify-by-agent-type
  "Classify task based on agent type."
  [agent-type]
  (when agent-type
    (let [task-type (get agent-type-to-task-type agent-type)]
      (when task-type
        {:type task-type
         :confidence 0.85
         :reason (format "Agent '%s' specializes in %s tasks" agent-type task-type)}))))

(defn classify-by-keywords
  "Classify task based on keyword analysis."
  [description title]
  (let [text (str description " " title)
        text-keywords (extract-keywords text)
        scores (into {}
                     (for [[task-type kw-set] task-type-keywords]
                       [task-type (count-keyword-matches text-keywords kw-set)]))]
    (when-let [best-match (first (sort-by val > scores))]
      (let [[task-type score] best-match]
        (when (> score 0)
          {:type task-type
           :confidence (min 0.8 (* 0.2 score))
           :reason (format "Text analysis suggests %s (matched %d keywords)" task-type score)})))))

(defn classify-by-context-size
  "Check if task requires large context handling."
  [task]
  (let [context-size (extract-context-size task)]
    (when (> context-size 100000)
      {:type :large-context
       :confidence 0.95
       :reason (format "Large context size (%d tokens) requires specialized models" context-size)})))

(defn classify-by-privacy
  "Check if task has privacy requirements."
  [task]
  (when (extract-privacy-requirements task)
    {:type :privacy-sensitive
     :confidence 1.0
     :reason "Privacy requirements mandate local model usage"}))

(defn classify-by-cost
  "Check if task has strict cost constraints."
  [task]
  (let [cost-constraint (extract-cost-constraints task)]
    (when cost-constraint
      {:type :cost-optimized
       :confidence 0.9
       :reason "Cost constraints require economical model selection"})))

(defn merge-classifications
  "Merge multiple classification results, prioritizing higher confidence."
  [classifications]
  (let [valid (filter identity classifications)
        sorted (sort-by :confidence > valid)]
    (when (seq sorted)
      (let [primary (first sorted)
            all-types (map :type sorted)
            all-reasons (map :reason sorted)]
        (assoc primary
               :alternative-types (vec (rest all-types))
               :all-reasons (vec all-reasons))))))

(defn classify-task
  "Classify a task to determine optimal model type.

   Input: {:phase :plan
           :agent-type :planner-agent
           :description \"Design architecture for...\"
           :title \"Architecture Design\"
           :context-tokens 50000
           :privacy-required false}

   Output: {:type :thinking-heavy
            :confidence 0.9
            :reason \"Phase 'plan' typically requires thinking-heavy tasks\"
            :alternative-types [:execution-focused]
            :all-reasons [...]}"
  [{:keys [phase agent-type description title] :as task}]
  (let [;; Try different classification strategies
        by-privacy (classify-by-privacy task)
        by-context (classify-by-context-size task)
        by-cost (classify-by-cost task)
        by-phase (classify-by-phase phase)
        by-agent (classify-by-agent-type agent-type)
        by-keywords (classify-by-keywords description title)

        ;; Priority order: privacy > context > cost > phase > agent > keywords
        classifications [by-privacy by-context by-cost by-phase by-agent by-keywords]
        result (merge-classifications classifications)]

    ;; Default to execution-focused if no clear classification
    (or result
        {:type :execution-focused
         :confidence 0.5
         :reason "Default classification for general development tasks"})))

(defn get-task-characteristics
  "Extract task characteristics for debugging/transparency.
   Returns map of extracted features."
  [{:keys [phase agent-type description title] :as task}]
  {:phase phase
   :agent-type agent-type
   :keywords (extract-keywords (str description " " title))
   :context-size (extract-context-size task)
   :privacy-required (extract-privacy-requirements task)
   :cost-constrained (extract-cost-constraints task)})
