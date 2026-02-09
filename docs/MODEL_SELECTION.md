# Intelligent Model Selection

Miniforge automatically selects the optimal AI model for each task based on task characteristics, optimizing for quality, cost, and performance.

## Overview

Instead of using a single model for everything, Miniforge intelligently chooses from 16 models across 4 providers:

- **Anthropic Claude**: Best overall quality and code generation
- **OpenAI Codex (GPT-5)**: Code-specialized models
- **Google Gemini**: Massive context windows (1-2M tokens), multimodal
- **Open Source**: Local models for privacy and cost optimization

## How It Works

### 1. Task Classification

When you create an agent or start a workflow phase, the system analyzes:

- **Phase**: planning, implementation, validation, testing, review
- **Agent Type**: planner, implementer, validator, tester, reviewer
- **Description**: keywords indicating complexity and type
- **Context Size**: number of tokens/files involved
- **Privacy Requirements**: whether local models are needed
- **Cost Constraints**: budget limitations

Example classifications:

```clojure
;; Planning phase
{:type :thinking-heavy
 :confidence 0.95
 :reason "Phase 'plan' requires deep reasoning"}

;; Implementation phase
{:type :execution-focused
 :confidence 0.90
 :reason "Code implementation task"}

;; Validation phase
{:type :simple-validation
 :confidence 0.95
 :reason "Quick syntax checks"}
```

### 2. Model Selection

Based on the classification, the system selects the optimal model:

**Thinking-Heavy Tasks** (Planning, Architecture, Research)

- Primary: **Opus 4.6** (best reasoning)
- Alternatives: GPT-5.3 Codex, Gemini 2.0 Flash Thinking
- Local: Llama 3.3 70B

**Execution-Focused Tasks** (Implementation, Testing, Review)

- Primary: **Sonnet 4.5** (best code generation)
- Alternatives: GPT-5.2 Codex, GPT-5.3 Codex
- Local: Qwen 2.5 Coder 32B, DeepSeek Coder 33B

**Simple Validation** (Syntax, Formatting, Quick Checks)

- Primary: **Haiku 4.5** (fastest, cheapest)
- Alternatives: Gemini 2.0 Flash, GPT-5.1 Codex Max
- Local: CodeLlama 34B

**Large Context** (>200k tokens)

- Primary: **Gemini Pro 2.0** (2M context window!)
- Alternatives: Gemini 2.0 Flash (1M), Opus 4.6 (200k)

**Privacy-Sensitive** (Proprietary code, offline)

- Primary: **Llama 3.3 70B** (best local reasoning)
- Alternatives: Qwen 2.5 Coder, DeepSeek Coder
- For Chinese: GLM-4 Plus

## Configuration

### Enable/Disable

Model selection is **enabled by default**. To disable:

```bash
# Environment variable
export MINIFORGE_MODEL_SELECTION_ENABLED=false

# Or in ~/.miniforge/config.edn
{:model-selection {:enabled false}}
```

### Selection Strategy

Choose how models are selected:

```bash
# Automatic (default) - best model for task type
export MINIFORGE_MODEL_SELECTION_STRATEGY=automatic

# Cost-optimized - cheapest sufficient model
export MINIFORGE_MODEL_SELECTION_STRATEGY=cost-optimized

# Speed - fastest model
export MINIFORGE_MODEL_SELECTION_STRATEGY=speed

# Or in config.edn
{:model-selection {:strategy :cost-optimized}}
```

### Cost Limits

Set maximum cost per task:

```bash
export MINIFORGE_MODEL_SELECTION_COST_LIMIT=0.05

# Or in config.edn
{:model-selection {:cost-limit-per-task 0.05}}
```

### Privacy Mode

Require local models only:

```bash
export MINIFORGE_MODEL_SELECTION_REQUIRE_LOCAL=true

# Or in config.edn
{:model-selection {:require-local true}}
```

### Override for Specific Tasks

Force a specific model for a workflow phase:

```clojure
;; In spec or workflow config
{:spec/model-override :opus-4.6}

;; Or when creating agent
(create-agent :implementer {:model "claude-opus-4-6"})
```

## Model Registry

### Anthropic Claude

**Opus 4.6** (Flagship)

- Best for: Complex reasoning, architecture, planning
- Reasoning: 10/10 | Code: 9/10 | Speed: 2/10 | Cost: $$
- Context: 200k tokens

**Sonnet 4.5** (Workhorse)

- Best for: Code implementation, testing, review
- Reasoning: 8/10 | Code: 10/10 | Speed: 7/10 | Cost: $
- Context: 200k tokens

**Haiku 4.5** (Efficient)

- Best for: Validation, formatting, quick checks
- Reasoning: 6/10 | Code: 7/10 | Speed: 10/10 | Cost: ¢
- Context: 200k tokens

### OpenAI Codex (GPT-5)

**GPT-5.3 Codex** (Flagship)

- Best for: Advanced code generation, polyglot programming
- Reasoning: 10/10 | Code: 10/10 | Speed: 6/10 | Cost: $$
- Context: 128k tokens

**GPT-5.2 Codex** (Workhorse)

- Best for: Standard code tasks, implementation
- Reasoning: 8/10 | Code: 9/10 | Speed: 7/10 | Cost: $
- Context: 128k tokens

**GPT-5.1 Codex Max** (Balanced)

- Best for: Fast code generation, large outputs
- Reasoning: 8/10 | Code: 9/10 | Speed: 8/10 | Cost: $
- Context: 128k tokens

**GPT-5.2** (General)

- Best for: Mixed code and text, documentation
- Reasoning: 8/10 | Code: 7/10 | Speed: 7/10 | Cost: $
- Context: 128k tokens

### Google Gemini

**Gemini Pro 2.0** (Workhorse)

- Best for: Very large codebases, multimodal
- Reasoning: 8/10 | Code: 8/10 | Speed: 7/10 | Cost: $
- Context: **2M tokens!**

**Gemini 2.0 Flash** (Fast)

- Best for: Fast execution, large context, batch operations
- Reasoning: 8/10 | Code: 8/10 | Speed: 9/10 | Cost: ¢
- Context: 1M tokens

**Gemini 2.0 Flash Thinking (Experimental)**

- Best for: Extended reasoning, complex problem decomposition
- Reasoning: 10/10 | Code: 8/10 | Speed: 3/10 | Cost: $$
- Context: 1M tokens

### Open Source / Local Models

**Llama 3.3 70B** (OSS Flagship)

- Best for: Privacy-sensitive, offline, cost-free
- Reasoning: 8/10 | Code: 7/10 | Speed: 5/10 | Cost: Free
- Context: 128k tokens

**Qwen 2.5 Coder 32B** (OSS Specialist)

- Best for: Fast local code generation
- Reasoning: 7/10 | Code: 9/10 | Speed: 8/10 | Cost: Free
- Context: 32k tokens

**DeepSeek Coder 33B** (OSS Specialist)

- Best for: Code-focused tasks, fill-in-the-middle
- Reasoning: 7/10 | Code: 9/10 | Speed: 6/10 | Cost: Free
- Context: 16k tokens

**GLM-4 Plus** (OSS Advanced)

- Best for: Chinese language, multilingual codebases
- Reasoning: 8/10 | Code: 7/10 | Speed: 6/10 | Cost: Free
- Context: 128k tokens

**CodeLlama 34B** (OSS Specialist)

- Best for: Fast local inference, low-resource environments
- Reasoning: 6/10 | Code: 8/10 | Speed: 8/10 | Cost: Free
- Context: 16k tokens

## Examples

### Automatic Selection in Action

```bash
# Planning phase - automatically selects Opus 4.6
miniforge workflow run my-spec.edn

# Output:
# 🎯 Model Auto-Selected: Opus 4.6
# Task: Plan architecture (thinking-heavy)
# Reasoning: Planning requires exceptional reasoning capabilities
# Cost: $0.03
```

### Cost-Optimized Workflow

```bash
# Enable cost optimization
export MINIFORGE_MODEL_SELECTION_STRATEGY=cost-optimized

# Run workflow
miniforge workflow run my-spec.edn

# Output shows cheaper models selected:
# Phase 1: Plan (Opus 4.6) - $0.03
# Phase 2-5: Implement (Sonnet 4.5) - $0.20
# Phase 6: Validate (Haiku 4.5) - $0.01
# Total: $0.24 (vs $0.84 all-Opus)
# Savings: 71%
```

### Privacy-First Development

```bash
# Require local models only
export MINIFORGE_MODEL_SELECTION_REQUIRE_LOCAL=true

# All phases use local models:
# Plan: Llama 3.3 70B
# Implement: Qwen 2.5 Coder 32B
# Validate: CodeLlama 34B
# Cost: $0 (local inference)
```

### Large Codebase Analysis

```clojure
;; Task with large context
{:phase :implement
 :description "Refactor entire monorepo"
 :context-tokens 1500000}

;; Automatically selects Gemini Pro 2.0 (2M context)
;; Output:
;; Selected Model: Gemini Pro 2.0 (Google)
;; Reason: Large context (1.5M tokens) requires specialized model
;; Context Window: 2M tokens
```

## Cost Savings

Example workflow with intelligent selection:

| Phase | Task Type | Model | Cost |
|-------|-----------|-------|------|
| 1. Plan | Thinking-heavy | Opus 4.6 | $0.03 |
| 2-5. Implement | Execution-focused | Sonnet 4.5 | $0.20 |
| 6. Validate | Simple-validation | Haiku 4.5 | $0.01 |
| 7. Review | Execution-focused | Sonnet 4.5 | $0.04 |
| **Total** | | | **$0.28** |

Compare to using Opus for everything: **$0.84**

**Savings: 67%** with maintained quality (right model for each task)

## Selection Matrix

Decision tree for model selection:

```
Task Type?
│
├─ Privacy Required? ──> LOCAL MODELS
│                        • Llama 3.3 70B (reasoning)
│                        • Qwen 2.5 Coder (code)
│
├─ Large Context (>200k)? ──> GEMINI
│                              • Gemini Pro 2.0 (2M)
│                              • Gemini 2.0 Flash (1M)
│
├─ Complex Reasoning? ──> TIER 1 THINKERS
│                        • Opus 4.6 (best overall)
│                        • GPT-5.3 Codex (code focus)
│
├─ Standard Code Work? ──> TIER 1 CODERS
│                         • Sonnet 4.5 (best codegen)
│                         • GPT-5.2/5.3 Codex
│
└─ Simple/Fast Tasks? ──> EFFICIENT MODELS
                          • Haiku 4.5 (cheapest)
                          • Gemini 2.0 Flash (fastest)
```

## Programmatic API

### Task Classification

```clojure
(require '[ai.miniforge.agent.interface :as agent])

;; Classify a task
(def classification
  (agent/classify-task
    {:phase :plan
     :agent-type :planner-agent
     :description "Design distributed architecture"}))

;; => {:type :thinking-heavy
;;     :confidence 0.95
;;     :reason "Phase 'plan' requires thinking-heavy tasks"
;;     :alternative-types [:execution-focused]
;;     :all-reasons [...]}
```

### Model Selection

```clojure
(require '[ai.miniforge.llm.interface :as llm])

;; Select model based on classification
(def selection
  (llm/select-model classification))

;; => {:model :opus-4.6
;;     :model-id "claude-opus-4-6"
;;     :provider :anthropic
;;     :backend :claude
;;     :task-type :thinking-heavy
;;     :confidence 0.95
;;     :strategy :automatic
;;     :rationale "..."}

;; Get explanation
(llm/explain-selection selection)
;; => "Model Auto-Selected: Opus 4.6
;;     Task: thinking-heavy (confidence: 95%)
;;     Reasoning: Planning requires exceptional reasoning..."
```

### Query Model Registry

```clojure
;; Get model by capability
(llm/get-models-by-capability :reasoning :exceptional)
;; => [:opus-4.6 :gpt-5.3-codex :gemini-2.0-flash-thinking-exp]

;; Get models by use-case
(llm/get-models-by-use-case :code-implementation)
;; => [:sonnet-4.5 :gpt-5.2-codex ...]

;; Get model profile
(llm/get-model :sonnet-4.5)
;; => {:model-id "claude-sonnet-4-5-20250929"
;;     :provider :anthropic
;;     :capabilities {:reasoning :excellent
;;                    :code-generation :exceptional
;;                    ...}
;;     ...}
```

### Agent Creation with Auto-Selection

```clojure
;; Create agent - automatically selects model based on role
(def planner (agent/create-agent :planner {:phase :plan}))
;; Logs: Model Auto-Selected: Opus 4.6 (thinking-heavy)

(def implementer (agent/create-agent :implementer {:phase :implement}))
;; Logs: Model Auto-Selected: Sonnet 4.5 (execution-focused)

(def validator (agent/create-agent :validator {:phase :validate}))
;; Logs: Model Auto-Selected: Haiku 4.5 (simple-validation)
```

## Best Practices

1. **Trust the defaults**: Automatic selection is optimized for quality and cost
2. **Use cost-optimized for batch operations**: When running many workflows
3. **Use privacy mode for sensitive code**: Financial, healthcare, proprietary
4. **Override only when necessary**: Let the system optimize
5. **Monitor costs**: Check logs for model selection and costs
6. **Local models for development**: Fast, free, no API calls

## Troubleshooting

### Model selection not working?

Check configuration:

```bash
miniforge config get model-selection
```

### Want to see selection reasoning?

Enable verbose logging:

```bash
export MINIFORGE_LOG_LEVEL=debug
miniforge workflow run spec.edn
```

### Override not working?

Ensure you're passing `:model` explicitly:

```clojure
(create-agent :implementer {:model "claude-opus-4-6"})
```

### Local models not available?

Install via Ollama:

```bash
ollama pull llama3.3:70b
ollama pull qwen2.5-coder:32b
ollama pull deepseek-coder:33b
```

## Further Reading

- [Model Registry](../components/llm/src/ai/miniforge/llm/model_registry.clj) - All model profiles
- [Task Classifier](../components/agent/src/ai/miniforge/agent/task_classifier.clj) - Classification logic
- [Model Selector](../components/llm/src/ai/miniforge/llm/model_selector.clj) - Selection strategies
- [Spec](../work/intelligent-model-selection.edn) - Original specification
