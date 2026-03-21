(ns ai.miniforge.llm.model-registry
  "Model registry with capability profiles for intelligent model selection.
   Layer 0: Model capability definitions (data)
   Layer 1: Query functions (by capability, use-case, task-type)
   Layer 2: Recommendation logic")

;------------------------------------------------------------------------------ Layer 0
;; Model capability definitions

(def capability-levels
  "Capability levels from lowest to highest"
  [:poor :fair :good :excellent :exceptional])

(def speed-levels
  "Speed capability levels from slowest to fastest"
  [:slow :moderate :balanced :fast :very-fast])

(def model-registry
  "Comprehensive registry of all supported models with their capabilities."
  {;; ========== Anthropic Claude Models ==========
   :opus-4.6
   {:model-id "claude-opus-4-6"
    :provider :anthropic
    :backend :claude
    :family :claude
    :tier :flagship
    :capabilities
    {:reasoning :exceptional
     :code-generation :excellent
     :speed :slow
     :cost :expensive
     :context-window 200000
     :output-tokens 16000
     :streaming true}
    :best-for
    ["Complex reasoning and planning"
     "Architecture decisions"
     "Novel problem solving"
     "Spec analysis and interpretation"
     "Multi-step logical deduction"
     "Design trade-off evaluation"
     "Meta-reasoning about workflows"]
    :use-cases
    #{:workflow-planning
      :architecture-design
      :spec-interpretation
      :complex-debugging
      :research-synthesis
      :strategic-decisions}}

   :sonnet-4.5
   {:model-id "claude-sonnet-4-5-20250929"
    :provider :anthropic
    :backend :claude
    :family :claude
    :tier :workhorse
    :capabilities
    {:reasoning :excellent
     :code-generation :exceptional
     :speed :balanced
     :cost :moderate
     :context-window 200000
     :output-tokens 16000
     :streaming true}
    :best-for
    ["Code implementation"
     "File editing and refactoring"
     "Test writing"
     "PR review and comments"
     "Standard development tasks"
     "Documentation generation"]
    :use-cases
    #{:code-implementation
      :file-editing
      :test-generation
      :code-review
      :documentation
      :standard-tasks}}

   :haiku-4.5
   {:model-id "claude-haiku-4-5-20251001"
    :provider :anthropic
    :backend :claude
    :family :claude
    :tier :efficient
    :capabilities
    {:reasoning :good
     :code-generation :good
     :speed :fast
     :cost :economical
     :context-window 200000
     :output-tokens 8000
     :streaming true}
    :best-for
    ["Simple validation tasks"
     "Syntax checking"
     "Formatting and linting"
     "Simple file operations"
     "Quick sanity checks"
     "Repetitive operations"]
    :use-cases
    #{:validation
      :syntax-checking
      :formatting
      :simple-edits
      :quick-checks
      :batch-operations}}

   ;; ========== Codex GPT-5 Models ==========
   :gpt-5.3-codex
   {:model-id "gpt-5.3-codex"
    :provider :openai
    :backend :codex
    :family :gpt-5
    :tier :flagship
    :capabilities
    {:reasoning :exceptional
     :code-generation :exceptional
     :speed :moderate
     :cost :expensive
     :context-window 128000
     :output-tokens 16000
     :streaming true}
    :best-for
    ["Advanced code generation"
     "Multi-language codebases"
     "Complex refactoring"
     "API integration"
     "Polyglot programming"]
    :use-cases
    #{:advanced-codegen
      :multi-language
      :api-integration
      :complex-refactoring
      :polyglot-tasks}}

   :gpt-5.2-codex
   {:model-id "gpt-5.2-codex"
    :provider :openai
    :backend :codex
    :family :gpt-5
    :tier :workhorse
    :capabilities
    {:reasoning :excellent
     :code-generation :excellent
     :speed :balanced
     :cost :moderate
     :context-window 128000
     :output-tokens 8000
     :streaming true}
    :best-for
    ["Standard code tasks"
     "Implementation work"
     "Code review"
     "Debugging"]
    :use-cases
    #{:code-implementation
      :standard-tasks
      :code-review
      :debugging}}

   :gpt-5.1-codex-max
   {:model-id "gpt-5.1-codex-max"
    :provider :openai
    :backend :codex
    :family :gpt-5
    :tier :balanced
    :capabilities
    {:reasoning :excellent
     :code-generation :excellent
     :speed :fast
     :cost :moderate
     :context-window 128000
     :output-tokens 12000
     :streaming true}
    :best-for
    ["Fast code generation"
     "Large output requirements"
     "Batch processing"]
    :use-cases
    #{:fast-codegen
      :batch-processing
      :large-outputs}}

   :gpt-5.2
   {:model-id "gpt-5.2"
    :provider :openai
    :backend :codex
    :family :gpt-5
    :tier :general
    :capabilities
    {:reasoning :excellent
     :code-generation :good
     :speed :balanced
     :cost :moderate
     :context-window 128000
     :output-tokens 8000
     :streaming true}
    :best-for
    ["General tasks"
     "Mixed code and text"
     "Documentation"]
    :use-cases
    #{:general-tasks
      :documentation
      :mixed-content}}

   ;; ========== Google Gemini Models ==========
   :gemini-2.0-flash-thinking-exp
   {:model-id "gemini-2.0-flash-thinking-exp"
    :provider :google
    :backend :gemini
    :family :gemini
    :tier :experimental
    :capabilities
    {:reasoning :exceptional
     :code-generation :excellent
     :speed :slow
     :cost :expensive
     :context-window 1000000
     :output-tokens 8192
     :streaming true}
    :best-for
    ["Extended reasoning chains"
     "Complex problem decomposition"
     "Mathematical reasoning"
     "Large context analysis"
     "Research synthesis"]
    :use-cases
    #{:extended-reasoning
      :problem-decomposition
      :large-context
      :research-tasks}}

   :gemini-2.0-flash
   {:model-id "gemini-2.0-flash"
    :provider :google
    :backend :gemini
    :family :gemini
    :tier :fast
    :capabilities
    {:reasoning :excellent
     :code-generation :excellent
     :speed :very-fast
     :cost :economical
     :context-window 1000000
     :output-tokens 8192
     :streaming true
     :multimodal true}
    :best-for
    ["Fast execution tasks"
     "Large context processing"
     "Multimodal inputs (images)"
     "Batch operations"]
    :use-cases
    #{:fast-execution
      :large-context
      :multimodal
      :batch-processing}}

   :gemini-pro-2.0
   {:model-id "gemini-2.0-pro"
    :provider :google
    :backend :gemini
    :family :gemini
    :tier :workhorse
    :capabilities
    {:reasoning :excellent
     :code-generation :excellent
     :speed :balanced
     :cost :moderate
     :context-window 2000000
     :output-tokens 8192
     :streaming true
     :multimodal true}
    :best-for
    ["Balanced performance"
     "Very large contexts"
     "Multimodal tasks"
     "Production workloads"]
    :use-cases
    #{:balanced-tasks
      :very-large-context
      :production-workloads}}

   ;; ========== Open Source / Local Models ==========
   :llama-3.3-70b
   {:model-id "llama-3.3-70b"
    :provider :meta
    :backend :ollama
    :family :llama
    :tier :oss-flagship
    :capabilities
    {:reasoning :excellent
     :code-generation :good
     :speed :moderate
     :cost :free
     :context-window 128000
     :output-tokens 8192
     :streaming true
     :local true}
    :best-for
    ["Privacy-sensitive tasks"
     "Offline development"
     "Cost-free inference"
     "Open source stacks"]
    :use-cases
    #{:privacy-tasks
      :offline-work
      :cost-optimization
      :oss-projects}}

   :qwen-2.5-coder-32b
   {:model-id "qwen-2.5-coder-32b"
    :provider :alibaba
    :backend :ollama
    :family :qwen
    :tier :oss-specialist
    :capabilities
    {:reasoning :good
     :code-generation :excellent
     :speed :fast
     :cost :free
     :context-window 32768
     :output-tokens 4096
     :streaming true
     :local true}
    :best-for
    ["Fast code generation"
     "Local development"
     "Code completion"
     "Small-medium codebases"]
    :use-cases
    #{:local-codegen
      :code-completion
      :fast-local-tasks}}

   :deepseek-coder-33b
   {:model-id "deepseek-coder-33b"
    :provider :deepseek
    :backend :ollama
    :family :deepseek
    :tier :oss-specialist
    :capabilities
    {:reasoning :good
     :code-generation :excellent
     :speed :moderate
     :cost :free
     :context-window 16384
     :output-tokens 4096
     :streaming true
     :local true}
    :best-for
    ["Code-focused tasks"
     "Local inference"
     "Fill-in-the-middle"
     "Code understanding"]
    :use-cases
    #{:code-generation
      :code-completion
      :local-inference}}

   :glm-4-plus
   {:model-id "glm-4-plus"
    :provider :zhipu
    :backend :ollama
    :family :glm
    :tier :oss-advanced
    :capabilities
    {:reasoning :excellent
     :code-generation :good
     :speed :balanced
     :cost :free
     :context-window 128000
     :output-tokens 4096
     :streaming true
     :local true
     :multilingual true}
    :best-for
    ["Chinese language tasks"
     "Multilingual codebases"
     "Local inference"
     "Asian market applications"]
    :use-cases
    #{:chinese-language
      :multilingual
      :local-inference
      :asian-markets}}

   :codellama-34b
   {:model-id "codellama-34b"
    :provider :meta
    :backend :ollama
    :family :llama
    :tier :oss-specialist
    :capabilities
    {:reasoning :good
     :code-generation :excellent
     :speed :fast
     :cost :free
     :context-window 16384
     :output-tokens 4096
     :streaming true
     :local true}
    :best-for
    ["Code completion"
     "Fast local inference"
     "Low-resource environments"
     "Code understanding"]
    :use-cases
    #{:code-completion
      :fast-local
      :low-resource
      :code-understanding}}})

;------------------------------------------------------------------------------ Layer 1
;; Query functions

(defn get-model
  "Get a model's full profile by keyword."
  [model-key]
  (get model-registry model-key))

(defn get-models-by-capability
  "Get models meeting or exceeding a capability level.
   Example: (get-models-by-capability :reasoning :excellent)
   Returns: [:opus-4.6 :sonnet-4.5 :gpt-5.3-codex ...]"
  [capability min-level]
  (let [level-idx (.indexOf capability-levels min-level)]
    (when (>= level-idx 0)
      (->> model-registry
           (filter (fn [[_k v]]
                     (let [model-level (get-in v [:capabilities capability])
                           model-idx (.indexOf capability-levels model-level)]
                       (and (>= model-idx 0)
                            (>= model-idx level-idx)))))
           (map first)
           (into [])))))

(defn get-models-by-speed
  "Get models meeting or exceeding a speed level.
   Example: (get-models-by-speed :fast)
   Returns: [:haiku-4.5 :gemini-2.0-flash ...]"
  [min-speed]
  (let [level-idx (.indexOf speed-levels min-speed)]
    (when (>= level-idx 0)
      (->> model-registry
           (filter (fn [[_k v]]
                     (let [model-speed (get-in v [:capabilities :speed])
                           model-idx (.indexOf speed-levels model-speed)]
                       (and (>= model-idx 0)
                            (>= model-idx level-idx)))))
           (map first)
           (into [])))))

(defn get-models-by-use-case
  "Get models that support a specific use-case.
   Example: (get-models-by-use-case :code-implementation)
   Returns: [:sonnet-4.5 :gpt-5.2-codex ...]"
  [use-case]
  (->> model-registry
       (filter (fn [[_k v]]
                 (contains? (:use-cases v) use-case)))
       (map first)
       (into [])))

(defn get-models-by-provider
  "Get all models from a specific provider."
  [provider]
  (->> model-registry
       (filter (fn [[_k v]] (= (:provider v) provider)))
       (map first)
       (into [])))

(defn get-local-models
  "Get all local models (for privacy-sensitive tasks)."
  []
  (->> model-registry
       (filter (fn [[_k v]] (get-in v [:capabilities :local])))
       (map first)
       (into [])))

(defn supports-large-context?
  "Check if model supports contexts larger than threshold (default 200k)."
  ([model-key] (supports-large-context? model-key 200000))
  ([model-key threshold]
   (when-let [model (get-model model-key)]
     (>= (get-in model [:capabilities :context-window]) threshold))))

;------------------------------------------------------------------------------ Layer 2
;; Recommendation logic

(def task-type-recommendations
  "Recommended models for each task type, organized by tier."
  {:thinking-heavy
   {:tier-1 [:opus-4.6 :gpt-5.3-codex :gemini-2.0-flash-thinking-exp]
    :tier-2 [:sonnet-4.5 :gpt-5.2-codex :gemini-pro-2.0]
    :tier-3-local [:llama-3.3-70b :glm-4-plus]
    :rationale "Exceptional reasoning needed for architecture decisions"}

   :execution-focused
   {:tier-1 [:sonnet-4.5 :gpt-5.2-codex :gpt-5.3-codex]
    :tier-2 [:gpt-5.1-codex-max :gemini-2.0-flash :gemini-pro-2.0]
    :tier-3-local [:qwen-2.5-coder-32b :deepseek-coder-33b :codellama-34b]
    :rationale "Balance of code capability and cost"}

   :simple-validation
   {:tier-1 [:haiku-4.5 :gemini-2.0-flash :gpt-5.1-codex-max]
    :tier-2 [:sonnet-4.5 :gpt-5.2]
    :tier-3-local [:codellama-34b :qwen-2.5-coder-32b]
    :rationale "Speed and cost efficiency for simple tasks"}

   :large-context
   {:tier-1 [:gemini-pro-2.0 :gemini-2.0-flash :opus-4.6]
    :tier-2 [:sonnet-4.5 :gpt-5.3-codex :llama-3.3-70b]
    :rationale "Need 1M+ token context windows"}

   :privacy-sensitive
   {:tier-1-local [:llama-3.3-70b :qwen-2.5-coder-32b :deepseek-coder-33b]
    :tier-2-local [:glm-4-plus :codellama-34b]
    :rationale "Must use local models for privacy"}

   :cost-optimized
   {:tier-1-free [:codellama-34b :qwen-2.5-coder-32b :deepseek-coder-33b]
    :tier-2-cheap [:haiku-4.5 :gemini-2.0-flash]
    :tier-3-moderate [:sonnet-4.5 :gpt-5.2]
    :rationale "Minimize cost while maintaining quality"}})

(defn recommend-models-for-task-type
  "Get recommended models for a task type.
   Returns map with :tier-1, :tier-2, :tier-3-local, and :rationale."
  [task-type]
  (get task-type-recommendations task-type))

(defn get-primary-recommendation
  "Get the primary recommended model for a task type.
   Returns the first model from tier-1."
  [task-type]
  (first (get-in task-type-recommendations [task-type :tier-1])))
