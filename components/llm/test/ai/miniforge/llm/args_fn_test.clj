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

(ns ai.miniforge.llm.args-fn-test
  "Tests for named backend argument builder functions.
   Verifies that extracted defn- functions produce the same args
   as their original inline anonymous counterparts."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

;; Private fn accessor
(defn- private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.llm.protocols.impl.llm-client sym)))

;; ============================================================================
;; claude-args
;; ============================================================================

(deftest claude-args-minimal-test
  (testing "minimal prompt produces [-p <prompt>]"
    (let [args ((private-fn 'claude-args) {:prompt "hello"})]
      (is (= ["-p" "hello"] args)))))

(deftest claude-args-streaming-test
  (testing "streaming adds output-format and verbose flags"
    (let [args ((private-fn 'claude-args) {:prompt "hi" :streaming? true})]
      (is (some #(= "--output-format" %) args))
      (is (some #(= "stream-json" %) args))
      (is (some #(= "--verbose" %) args))
      (is (= "hi" (last args))))))

(deftest claude-args-mcp-config-test
  (testing "mcp-config adds --mcp-config flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :mcp-config "/tmp/mcp.json"})]
      (is (some #(= "--mcp-config" %) args))
      (is (some #(= "/tmp/mcp.json" %) args)))))

(deftest claude-args-allowed-tools-test
  (testing "mcp maps format as mcp__<server>__<tool>, joined with commas"
    (let [args ((private-fn 'claude-args)
                {:prompt "p"
                 :mcp-allowed-tools
                 [{:mcp/server :context :mcp/tool :context_read}
                  {:mcp/server :context :mcp/tool :context_grep}]})]
      (is (some #(= "--allowedTools" %) args))
      (is (some #(= "mcp__context__context_read,mcp__context__context_grep" %) args))))

  (testing "bare keywords format as the native tool name"
    (let [args ((private-fn 'claude-args)
                {:prompt "p"
                 :mcp-allowed-tools [:Write :Edit]})]
      (is (some #(= "Write,Edit" %) args))))

  (testing "mixed maps + keywords format correctly"
    (let [args ((private-fn 'claude-args)
                {:prompt "p"
                 :mcp-allowed-tools
                 [{:mcp/server :context :mcp/tool :context_read}
                  :Write]})]
      (is (some #(= "mcp__context__context_read,Write" %) args)))))

(deftest claude-mcp-allowlist-string-test
  (testing "keyword server + tool → mcp__<server>__<tool>"
    (is (= "mcp__context__context_read"
           (impl/claude-mcp-allowlist-string
             [{:mcp/server :context :mcp/tool :context_read}]))))

  (testing "bare keyword → (name kw) — native tools"
    (is (= "Write" (impl/claude-mcp-allowlist-string [:Write])))
    (is (= "Write,Edit" (impl/claude-mcp-allowlist-string [:Write :Edit]))))

  (testing "multiple mcp entries joined with commas"
    (is (= "mcp__ctx__a,mcp__ctx__b"
           (impl/claude-mcp-allowlist-string
             [{:mcp/server :ctx :mcp/tool :a}
              {:mcp/server :ctx :mcp/tool :b}]))))

  (testing "mixed mcp maps + native keywords"
    (is (= "mcp__ctx__read,Write,mcp__ctx__grep"
           (impl/claude-mcp-allowlist-string
             [{:mcp/server :ctx :mcp/tool :read}
              :Write
              {:mcp/server :ctx :mcp/tool :grep}]))))

  (testing "empty vector produces empty string"
    (is (= "" (impl/claude-mcp-allowlist-string [])))))

(deftest claude-args-disallowed-tools-test
  (testing "disallowed-tools adds --disallowedTools"
    (let [args ((private-fn 'claude-args) {:prompt "p" :disallowed-tools ["bad"]})]
      (is (some #(= "--disallowedTools" %) args))
      (is (some #(= "bad" %) args)))))

(deftest claude-args-system-prompt-test
  (testing "system prompt adds --system-prompt flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :system "You are helpful"})]
      (is (some #(= "--system-prompt" %) args))
      (is (some #(= "You are helpful" %) args)))))

(deftest claude-args-budget-test
  (testing "explicit budget-usd sets --max-budget-usd"
    (let [args ((private-fn 'claude-args) {:prompt "p" :budget-usd 5.0})]
      (is (some #(= "--max-budget-usd" %) args))
      (is (some #(= "5.0" %) args)))))

(deftest claude-args-max-turns-test
  (testing "max-turns adds --max-turns flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :max-turns 10})]
      (is (some #(= "--max-turns" %) args))
      (is (some #(= "10" %) args)))))

(deftest claude-args-supervision-settings-test
  (testing "supervision settings path adds --settings flag"
    (let [args ((private-fn 'claude-args)
                {:prompt "p" :supervision {:settings-path "/tmp/s.json"}})]
      (is (some #(= "--settings" %) args))
      (is (some #(= "/tmp/s.json" %) args)))))

(deftest claude-args-model-test
  (testing "model adds --model flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :model "claude-sonnet-4-6"})]
      (is (some #(= "--model" %) args))
      (is (some #(= "claude-sonnet-4-6" %) args)))))

(deftest claude-args-resume-test
  (testing "resume adds --resume flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :resume "session-abc"})]
      (is (some #(= "--resume" %) args))
      (is (some #(= "session-abc" %) args)))))

(deftest claude-args-prompt-always-last-test
  (testing "prompt is always the last argument"
    (let [args ((private-fn 'claude-args)
                {:prompt "the-prompt" :streaming? true :system "sys"
                 :max-turns 5 :budget-usd 1.0 :model "claude-sonnet-4-6"})]
      (is (= "the-prompt" (last args))))))

;; ============================================================================
;; codex-args
;; ============================================================================

(deftest codex-args-minimal-test
  (testing "minimal prompt produces exec with explicit sandbox + approval flags"
    (let [args ((private-fn 'codex-args) {:prompt "fix bug"})]
      (is (= "exec" (first args)))
      (is (some #(= "--json" %) args))
      ;; Explicit sandbox + approval-policy config replaces the deprecated
      ;; --full-auto alias.
      (is (some #(= "--sandbox=workspace-write" %) args))
      (is (some #(re-matches #"approval_policy=\"?never\"?" %) args))
      (is (some #(= "--skip-git-repo-check" %) args))
      (is (= "fix bug" (last args))))))

(deftest codex-args-preflight-safe-config-test
  (testing "config overrides are safe before artifact MCP config exists"
    (let [args ((private-fn 'codex-args) {:prompt "p"})]
      ;; The artifact MCP config writer marks the server required. Passing
      ;; only mcp_servers.artifact.required=true here creates an incomplete
      ;; server table and current Codex rejects it during generic preflight.
      (is (not (some #(= "mcp_servers.artifact.required=true" %) args)))
      ;; approval_policy=never is set via -c so any config.toml default
      ;; cannot relax it.
      (is (some #(re-matches #"approval_policy=\"?never\"?" %) args)))))

(deftest codex-args-model-test
  (testing "model adds -m flag"
    (let [args ((private-fn 'codex-args) {:prompt "p" :model "gpt-4o"})]
      (is (some #(= "-m" %) args))
      (is (some #(= "gpt-4o" %) args)))))

(deftest codex-args-system-test
  (testing "system prompt adds -c flag with JSON-encoded value"
    (let [args ((private-fn 'codex-args) {:prompt "p" :system "be helpful"})]
      (is (some #(str/starts-with? % "system_prompt=") args)))))

;; ============================================================================
;; cursor-args
;; ============================================================================

(deftest cursor-args-minimal-test
  (testing "minimal prompt produces [-p --force <prompt>] (autonomous writes)"
    (let [args ((private-fn 'cursor-args) {:prompt "fix it"})]
      (is (= ["-p" "--force" "fix it"] args)))))

(deftest cursor-args-no-approve-mcps-test
  (testing "MCP scoping is via the permissions allowlist, not --approve-mcps"
    (let [args ((private-fn 'cursor-args) {:prompt "p" :mcp-allowed-tools ["t1"]})]
      (is (not (some #(= "--approve-mcps" %) args)))
      (is (= "p" (last args))))))

(deftest cursor-args-model-test
  (testing "model adds --model <model> before the prompt"
    (let [args ((private-fn 'cursor-args) {:prompt "p" :model "gpt-5.2"})]
      (is (= ["-p" "--force" "--model" "gpt-5.2" "p"] args)))))

(deftest cursor-args-system-test
  (testing "system is prepended to the prompt (no system-prompt flag on cursor)"
    (let [args ((private-fn 'cursor-args) {:prompt "do it" :system "be terse"})]
      (is (= ["-p" "--force" "be terse\n\ndo it"] args)))))

;; ============================================================================
;; opencode-args
;; ============================================================================

(deftest opencode-args-minimal-test
  (testing "minimal prompt invokes non-interactive run"
    (let [args ((private-fn 'opencode-args) {:prompt "explain"})]
      (is (= ["run" "explain"] args)))))

(deftest opencode-args-model-and-agent-test
  (testing "model and agent pass through to OpenCode"
    (let [args ((private-fn 'opencode-args)
                {:prompt "fix it"
                 :model "anthropic/claude-sonnet-4-5"
                 :agent "miniforge-implementer"})]
      (is (= "run" (first args)))
      (is (some #(= "--model" %) args))
      (is (some #(= "anthropic/claude-sonnet-4-5" %) args))
      (is (some #(= "--agent" %) args))
      (is (some #(= "miniforge-implementer" %) args))
      (is (= "fix it" (last args))))))

(deftest opencode-args-session-and-attach-test
  (testing "session reuse and serve attachment flags are preserved"
    (let [args ((private-fn 'opencode-args)
                {:prompt "continue"
                 :attach "http://localhost:4096"
                 :session "sess_123"
                 :fork? true})]
      (is (= ["run"
              "--attach" "http://localhost:4096"
              "--session" "sess_123"
              "--fork"
              "continue"]
             args)))))

(deftest opencode-args-files-test
  (testing "attached files expand into repeated --file flags"
    (let [args ((private-fn 'opencode-args)
                {:prompt "review"
                 :files ["README.md" "src/core.clj"]})]
      (is (= ["run"
              "--file" "README.md"
              "--file" "src/core.clj"
              "review"]
             args)))))

;; ============================================================================
;; echo-args
;; ============================================================================

(deftest echo-args-test
  (testing "echo backend returns prompt in a vector"
    (let [args ((private-fn 'echo-args) {:prompt "test-echo"})]
      (is (= ["test-echo"] args)))))

;; ============================================================================
;; backends map wiring
;; ============================================================================

(deftest backends-args-fn-wired-test
  (testing "all backends with :args-fn reference named functions, not lambdas"
    (doseq [[k backend] impl/backends
            :when (:args-fn backend)]
      (is (fn? (:args-fn backend))
          (str "Backend " k " should have a function :args-fn")))))

(deftest opencode-backend-wiring-test
  (testing "OpenCode backend delegates auth/provider handling to OpenCode"
    (is (= "opencode" (get-in impl/backends [:opencode :cmd])))
    (is (= "OpenCode" (get-in impl/backends [:opencode :provider])))
    (is (false? (get-in impl/backends [:opencode :streaming?])))
    (is (not (contains? (get impl/backends :opencode) :api-key-var)))
    (is (= :argv (get-in impl/backends [:opencode :prompt-via])))))
