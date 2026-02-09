#!/usr/bin/env bb
;; Demonstrates intelligent model selection in action

(require '[clojure.pprint :refer [pprint]])

;; Simulated API (replace with actual requires when running in REPL)
(def classify-task identity)
(def select-model identity)
(def get-model identity)

(println "\n═══════════════════════════════════════════════════════════")
(println "        Intelligent Model Selection Demo")
(println "═══════════════════════════════════════════════════════════\n")

;; Example 1: Planning Task
(println "📋 Example 1: Planning Task")
(println "─────────────────────────────────────────────────────────\n")

(def planning-task
  {:phase :plan
   :agent-type :planner-agent
   :description "Design a distributed microservices architecture with event sourcing"
   :title "Architecture Design"})

(println "Input Task:")
(pprint planning-task)

(println "\n🎯 Expected Classification:")
(println "  Type: :thinking-heavy")
(println "  Confidence: 0.95")
(println "  Reason: Planning requires deep reasoning")

(println "\n✨ Expected Model Selection:")
(println "  Model: Opus 4.6 (claude-opus-4-6)")
(println "  Provider: Anthropic")
(println "  Reasoning: Exceptional reasoning capability (10/10)")
(println "  Cost: ~$0.03")

;; Example 2: Implementation Task
(println "\n\n📋 Example 2: Implementation Task")
(println "─────────────────────────────────────────────────────────\n")

(def implementation-task
  {:phase :implement
   :agent-type :implementer-agent
   :description "Implement user authentication service with JWT tokens"
   :title "Auth Implementation"})

(println "Input Task:")
(pprint implementation-task)

(println "\n🎯 Expected Classification:")
(println "  Type: :execution-focused")
(println "  Confidence: 0.90")
(println "  Reason: Code implementation task")

(println "\n✨ Expected Model Selection:")
(println "  Model: Sonnet 4.5 (claude-sonnet-4-5-20250929)")
(println "  Provider: Anthropic")
(println "  Code Generation: Exceptional (10/10)")
(println "  Cost: ~$0.05")

;; Example 3: Validation Task
(println "\n\n📋 Example 3: Validation Task")
(println "─────────────────────────────────────────────────────────\n")

(def validation-task
  {:phase :validate
   :agent-type :validator-agent
   :description "Validate syntax and check formatting"
   :title "Quick Validation"})

(println "Input Task:")
(pprint validation-task)

(println "\n🎯 Expected Classification:")
(println "  Type: :simple-validation")
(println "  Confidence: 0.95")
(println "  Reason: Simple validation task")

(println "\n✨ Expected Model Selection:")
(println "  Model: Haiku 4.5 (claude-haiku-4-5-20251001)")
(println "  Provider: Anthropic")
(println "  Speed: Very Fast (10/10)")
(println "  Cost: ~$0.01 (cheapest)")

;; Example 4: Large Context Task
(println "\n\n📋 Example 4: Large Context Task")
(println "─────────────────────────────────────────────────────────\n")

(def large-context-task
  {:phase :implement
   :description "Refactor entire monorepo with 500k lines of code"
   :context-tokens 1500000})

(println "Input Task:")
(pprint large-context-task)

(println "\n🎯 Expected Classification:")
(println "  Type: :large-context")
(println "  Confidence: 0.95")
(println "  Reason: Context size 1.5M tokens requires specialized model")

(println "\n✨ Expected Model Selection:")
(println "  Model: Gemini Pro 2.0 (gemini-2.0-pro)")
(println "  Provider: Google")
(println "  Context Window: 2M tokens!")
(println "  Cost: ~$0.05")

;; Example 5: Privacy-Sensitive Task
(println "\n\n📋 Example 5: Privacy-Sensitive Task")
(println "─────────────────────────────────────────────────────────\n")

(def privacy-task
  {:phase :implement
   :agent-type :implementer-agent
   :privacy-required true
   :description "Implement banking transaction processing"
   :title "Secure Banking Feature"})

(println "Input Task:")
(pprint privacy-task)

(println "\n🎯 Expected Classification:")
(println "  Type: :privacy-sensitive")
(println "  Confidence: 1.0")
(println "  Reason: Privacy requirements mandate local models")

(println "\n✨ Expected Model Selection:")
(println "  Model: Llama 3.3 70B")
(println "  Provider: Meta")
(println "  Local: Yes (runs on your hardware)")
(println "  Cost: $0 (free local inference)")

;; Cost Comparison
(println "\n\n💰 Cost Comparison: Multi-Phase Workflow")
(println "═══════════════════════════════════════════════════════════\n")

(println "Workflow: 7-phase software development")
(println "  Phase 1: Plan (Opus 4.6)          $0.03")
(println "  Phase 2: Implement (Sonnet 4.5)   $0.05")
(println "  Phase 3: Implement (Sonnet 4.5)   $0.05")
(println "  Phase 4: Implement (Sonnet 4.5)   $0.05")
(println "  Phase 5: Test (Sonnet 4.5)        $0.05")
(println "  Phase 6: Validate (Haiku 4.5)     $0.01")
(println "  Phase 7: Review (Sonnet 4.5)      $0.04")
(println "                                    ─────")
(println "  Total with Intelligent Selection: $0.28")
(println)
(println "  Compare to all-Opus:              $0.84")
(println "  Compare to all-Sonnet:            $0.35")
(println)
(println "  Savings vs Opus:   67% ($0.56)")
(println "  Savings vs Sonnet: 20% ($0.07)")
(println "  Quality: Maintained (right model for each task)")

;; Selection Strategies
(println "\n\n🎛️  Selection Strategies")
(println "═══════════════════════════════════════════════════════════\n")

(println "Strategy: :automatic (default)")
(println "  - Selects best model for task type")
(println "  - Balances quality and cost")
(println "  - Recommended for most workflows")
(println)

(println "Strategy: :cost-optimized")
(println "  - Prefers cheapest sufficient model")
(println "  - Uses free local models when possible")
(println "  - Good for batch operations, experimentation")
(println)

(println "Strategy: :speed")
(println "  - Prefers fastest models")
(println "  - Uses Haiku, Gemini Flash")
(println "  - Good for interactive workflows")

;; Configuration Examples
(println "\n\n⚙️  Configuration Examples")
(println "═══════════════════════════════════════════════════════════\n")

(println "Enable cost optimization:")
(println "  export MINIFORGE_MODEL_SELECTION_STRATEGY=cost-optimized")
(println)

(println "Set cost limit per task:")
(println "  export MINIFORGE_MODEL_SELECTION_COST_LIMIT=0.05")
(println)

(println "Require local models (privacy mode):")
(println "  export MINIFORGE_MODEL_SELECTION_REQUIRE_LOCAL=true")
(println)

(println "Disable automatic selection:")
(println "  export MINIFORGE_MODEL_SELECTION_ENABLED=false")

;; Provider Strengths
(println "\n\n🏆 Provider Strengths")
(println "═══════════════════════════════════════════════════════════\n")

(println "Anthropic Claude:")
(println "  ✓ Best overall reasoning quality")
(println "  ✓ Exceptional code generation (Sonnet)")
(println "  ✓ 200k context window")
(println "  ✓ Choose for: Standard to complex tasks")
(println)

(println "OpenAI Codex (GPT-5):")
(println "  ✓ Code-specialized models")
(println "  ✓ Polyglot programming")
(println "  ✓ 128k context")
(println "  ✓ Choose for: Advanced code tasks")
(println)

(println "Google Gemini:")
(println "  ✓ Massive context (1-2M tokens!)")
(println "  ✓ Very fast (Flash models)")
(println "  ✓ Multimodal support")
(println "  ✓ Choose for: Large codebases, speed")
(println)

(println "Open Source (Local):")
(println "  ✓ Privacy-first (runs locally)")
(println "  ✓ Cost-free inference")
(println "  ✓ Offline development")
(println "  ✓ Choose for: Sensitive code, cost optimization")

(println "\n═══════════════════════════════════════════════════════════")
(println "For more information, see docs/MODEL_SELECTION.md")
(println "═══════════════════════════════════════════════════════════\n")
