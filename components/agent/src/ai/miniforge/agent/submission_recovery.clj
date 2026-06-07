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

(ns ai.miniforge.agent.submission-recovery
  "Agent-agnostic recovery for the 'produced prose but submitted no artifact'
   failure mode.

   When an agent turn ends with usable plan/code content in its output but
   NEITHER submission channel delivered an artifact — no MCP submit_artifact
   and no worktree-promoted `.miniforge/<role>.edn` (and, for the implementer,
   no files written) — the work exists in the turn's text but the phase has
   nothing to carry forward, so it fails. This was first fixed for the planner
   (PR #997); intermittent submission affects every agent, so the trigger and
   the short retry-turn runner live here. Both the planner and the implementer
   route their recovery through `run-recovery-session`; verify/review/release
   can adopt the same path as they're shown to need it.

   The recovery is a SINGLE bounded follow-up turn that re-feeds the prior
   content and asks only for the Write/submit — no further exploration. Each
   agent supplies its own role-specific retry prompt, mcp-opts, and normalize
   step; this namespace owns the shared trigger predicate and the retry-session
   runner."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.llm.interface :as llm]))

(def ^:private recoverable-error-types
  "Backend error classes whose stdout still holds the agent's work, so a
   submission-only retry can recover it.

   Deliberately EXCLUDES `llm/context-overflow-error-type`
   (\"context_overflow\"): a prompt that exceeded the model's context
   window left no artifact to recover, and a retry re-sends an over-budget
   prompt — so it must terminate, not retry (see that var's docstring)."
  #{"adaptive_timeout" "cli_error"})

(defn submission-retry?
  "True when an agent turn produced usable content but delivered NO artifact
   (no submitted structured artifact AND no parseable final-message content),
   so a short submission-only retry can recover the work already present in the
   turn's output.

   Fires in TWO cases:
   (a) a clean SUCCESS with prose-only narration (`response-content` non-empty)
       — the agent reasoned out the work but never wrote/submitted it; and
   (b) a recoverable ERROR (adaptive_timeout / cli_error) that still left
       useful stdout to retry from.

   `submitted-artifact` and `parsed-content` are the two artifact channels from
   the normalized result; `response-content` is the agent's prose output."
  [llm-response submitted-artifact parsed-content response-content]
  (and (nil? submitted-artifact)
       (nil? parsed-content)
       (or
        ;; (a) clean success but no artifact — prose-only narration
        (and (llm/success? llm-response)
             (seq response-content))
        ;; (b) recoverable error with useful stdout
        (let [err (llm/get-error llm-response)]
          (and (not (llm/success? llm-response))
               (seq (:stdout err))
               (contains? recoverable-error-types (:type err)))))))

(defn run-recovery-session
  "Run ONE short submission-only retry turn and return its RAW session channels
   `{:llm-result :artifact :worktree-artifacts :context-misses
     :pre-session-snapshot :session-mode}`. The caller re-normalizes
   (role-specific — e.g. the implementer also re-collects its file artifact).

   `opts`:
     :context          execution context (threaded into `with-session`)
     :llm-client       resolved LLM client for the role
     :retry-prompt     the role's submission-only prompt (prior content folded in)
     :effective-system the same base+addendum system prompt as the main turn,
                       so the retry enforces the identical compiled policy pack
     :on-chunk         optional streaming callback (pings the watchdog)
     :mcp-opts-fn      (fn [session] -> mcp-opts) — builds the per-role session
                       opts (model, disallowed-tools, budget, turn cap, workdir)"
  [{:keys [context llm-client retry-prompt effective-system on-chunk mcp-opts-fn]}]
  (artifact-session/with-session context
    (fn [session]
      (let [mcp-opts (mcp-opts-fn session)
            opts     (merge {:system effective-system} mcp-opts)]
        (if on-chunk
          (llm/chat-stream llm-client retry-prompt on-chunk opts)
          (llm/chat llm-client retry-prompt opts))))))
