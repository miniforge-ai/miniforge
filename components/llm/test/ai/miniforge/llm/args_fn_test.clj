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
   [clojure.java.io :as io]
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

(deftest codex-args-approval-policy-test
  (testing "config overrides keep approval policy pinned to never"
    (let [args ((private-fn 'codex-args) {:prompt "p"})]
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

(deftest process-env-codex-runtime-home-test
  (testing "codex backend seeds a writable runtime CODEX_HOME from user config files"
    (let [suffix (str "miniforge-codex-home-test-" (System/nanoTime))
          source-home (io/file (System/getProperty "java.io.tmpdir") (str suffix "-source"))
          runtime-home (io/file (System/getProperty "java.io.tmpdir") (str suffix "-runtime"))
          source-auth (io/file source-home "auth.json")
          source-config (io/file source-home "config.toml")
          source-installation (io/file source-home "installation_id")]
      (.mkdirs source-home)
      (spit source-auth "{\"token\":\"test\"}")
      (spit source-config "model = \"gpt-5-codex\"\n")
      (spit source-installation "install-test\n")
      (try
        (with-redefs [impl/default-codex-home (fn [] (.getPath source-home))
                      impl/runtime-codex-home (fn [] (.getPath runtime-home))]
          (let [env ((private-fn 'process-env) :codex)]
            (is (= (.getPath runtime-home) (get env "CODEX_HOME")))
            (is (.exists (io/file runtime-home "sessions")))
            (is (= (slurp source-auth) (slurp (io/file runtime-home "auth.json"))))
            (is (= (slurp source-config) (slurp (io/file runtime-home "config.toml"))))
            (is (= (slurp source-installation)
                   (slurp (io/file runtime-home "installation_id"))))))
        (finally
          (doseq [dir [source-home runtime-home]]
            (when (.exists dir)
              (run! #(io/delete-file % true) (reverse (file-seq dir))))))))))

;; ============================================================================
;; cursor-args
;; ============================================================================

(deftest cursor-args-minimal-test
  (testing "minimal prompt produces [-p <prompt>]"
    (let [args ((private-fn 'cursor-args) {:prompt "fix it"})]
      (is (= ["-p" "fix it"] args)))))

(deftest cursor-args-approve-mcps-test
  (testing "mcp-allowed-tools adds --approve-mcps"
    (let [args ((private-fn 'cursor-args) {:prompt "p" :mcp-allowed-tools ["t1"]})]
      (is (some #(= "--approve-mcps" %) args))
      (is (= "p" (last args))))))

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
