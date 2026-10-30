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
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.llm.protocols.impl.llm-client.cli-args :as cli-args]
   [ai.miniforge.llm.protocols.impl.llm-client.completion :as completion]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

;; Private fn accessor
(defn- ^{:stratum 0} private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.llm.protocols.impl.llm-client sym)))

(def ^{:stratum 0} ^:private supervised
  "Hook settings, as an artifact session passes them."
  {:supervision {:settings-path "/tmp/s.json"}})

(deftest ^{:stratum 0} claude-mcp-allowlist-string-test
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

;; ============================================================================
;; backends map wiring
;; ============================================================================
(deftest ^{:stratum 0} backends-args-fn-wired-test
  (testing "all backends with :args-fn reference named functions, not lambdas"
    (doseq [[k backend] impl/backends
            :when (:args-fn backend)]
      (is (fn? (:args-fn backend))
          (str "Backend " k " should have a function :args-fn")))))

(deftest ^{:stratum 0} opencode-backend-wiring-test
  (testing "OpenCode backend delegates auth/provider handling to OpenCode"
    (is (= "opencode" (get-in impl/backends [:opencode :cmd])))
    (is (= "OpenCode" (get-in impl/backends [:opencode :provider])))
    (is (false? (get-in impl/backends [:opencode :streaming?])))
    (is (not (contains? (get impl/backends :opencode) :api-key-env)))
    (is (= :argv (get-in impl/backends [:opencode :prompt-via])))))

(deftest ^{:stratum 0} with-client-config-test
  (testing "an isolated client isolates every request"
    (is (true? (:isolated? (completion/with-client-config {:prompt "p"} {:isolated? true})))))

  (testing "a request may ask for isolation on a plain client"
    (is (true? (:isolated? (completion/with-client-config {:prompt "p" :isolated? true} {})))))

  (testing "model and context size still pass through"
    (is (= {:prompt "p" :model "m" :num-ctx 8}
           (completion/with-client-config {:prompt "p"} {:model "m" :num-ctx 8})))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; claude-args
;; ============================================================================
(deftest ^{:stratum 1} claude-args-minimal-test
  (testing "a minimal prompt with hook settings loads only those settings and refuses what the hook does not allow"
    (let [args ((private-fn 'claude-args) {:prompt "hello"
                                           :supervision {:settings-path "/tmp/s.json"}})]
      (is (= ["-p" "--setting-sources" "" "--permission-mode" "dontAsk" "--settings" "/tmp/s.json" "hello"]
             args)))))

(deftest ^{:stratum 1} claude-args-isolated-test
  (testing "a call with no hook settings gets no tools, settings, skills, or host MCP config"
    (is (= ["-p" "--tools" "" "--setting-sources" "" "--disable-slash-commands"
            "--strict-mcp-config" "hello"]
           ((private-fn 'claude-args) {:prompt "hello"}))))

  (testing "isolated? isolates a call that has hook settings"
    (let [args ((private-fn 'claude-args) {:prompt "p"
                                           :isolated? true
                                           :supervision {:settings-path "/tmp/s.json"}})]
      (is (= ["--tools" "" "--setting-sources" "" "--disable-slash-commands" "--strict-mcp-config"]
             (->> args (drop-while #(not= "--tools" %)) (take 6))))
      (is (= "p" (last args)))))

  (testing "with an --mcp-config, isolation does not repeat --strict-mcp-config"
    (let [args ((private-fn 'claude-args) {:prompt "p" :mcp-config "/tmp/mcp.json"})]
      (is (some #{"--setting-sources"} args))
      (is (= 1 (count (filter #(= "--strict-mcp-config" %) args))))))

  (testing "a call with hook settings and no isolated? keeps its tools, and gets the session flags"
    (let [args ((private-fn 'claude-args) {:prompt "p"
                                           :supervision {:settings-path "/tmp/s.json"}})]
      (is (not-any? #{"--tools" "--disable-slash-commands"} args))
      (is (= ["--setting-sources" "" "--permission-mode" "dontAsk"]
             (->> args (drop-while #(not= "--setting-sources" %)) (take 4)))))))

(deftest ^{:stratum 1} claude-args-streaming-test
  (testing "streaming adds output-format and verbose flags"
    (let [args ((private-fn 'claude-args) {:prompt "hi" :streaming? true})]
      (is (some #(= "--output-format" %) args))
      (is (some #(= "stream-json" %) args))
      (is (some #(= "--verbose" %) args))
      (is (= "hi" (last args))))))

(deftest ^{:stratum 1} claude-args-mcp-config-test
  (testing "mcp-config adds --mcp-config flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :mcp-config "/tmp/mcp.json"})]
      (is (some #(= "--mcp-config" %) args))
      (is (some #(= "/tmp/mcp.json" %) args))))

  (testing "mcp-config also adds --strict-mcp-config so host MCP config does not leak"
    (let [args ((private-fn 'claude-args) {:prompt "p" :mcp-config "/tmp/mcp.json"})]
      (is (some #(= "--strict-mcp-config" %) args))
      (is (= ["--mcp-config" "/tmp/mcp.json" "--strict-mcp-config"]
             (->> args
                  (drop-while #(not= "--mcp-config" %))
                  (take 3)))
          "--strict-mcp-config immediately follows the --mcp-config path")))

  (testing "a call with hook settings and no mcp-config gets no --strict-mcp-config"
    (let [args ((private-fn 'claude-args) {:prompt "p"
                                           :supervision {:settings-path "/tmp/s.json"}})]
      (is (not (some #(= "--strict-mcp-config" %) args))))))

(deftest ^{:stratum 1} claude-args-allowed-tools-test
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

(deftest ^{:stratum 1} claude-args-session-gets-no-allowed-tools-test
  (testing "a session call takes its allow rules from its settings file, not --allowedTools"
    (let [args ((private-fn 'claude-args)
                {:prompt "p"
                 :supervision {:settings-path "/tmp/s.json"}
                 :mcp-allowed-tools [{:mcp/server :context :mcp/tool :context_write} :Write]})]
      (is (not (some #{"--allowedTools"} args))))))

(deftest ^{:stratum 1} claude-args-disallowed-tools-test
  (testing "disallowed-tools adds --disallowedTools"
    (let [args ((private-fn 'claude-args) {:prompt "p" :disallowed-tools ["bad"]})]
      (is (some #(= "--disallowedTools" %) args))
      (is (some #(= "bad" %) args)))))

(deftest ^{:stratum 1} claude-args-system-prompt-test
  (testing "system prompt adds --system-prompt flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :system "You are helpful"})]
      (is (some #(= "--system-prompt" %) args))
      (is (some #(= "You are helpful" %) args)))))

(deftest ^{:stratum 1} claude-args-budget-test
  (testing "explicit budget-usd sets --max-budget-usd"
    (let [args ((private-fn 'claude-args) {:prompt "p" :budget-usd 5.0})]
      (is (some #(= "--max-budget-usd" %) args))
      (is (some #(= "5.0" %) args)))))

(deftest ^{:stratum 1} claude-args-max-turns-test
  (testing "max-turns adds --max-turns flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :max-turns 10})]
      (is (some #(= "--max-turns" %) args))
      (is (some #(= "10" %) args)))))

(deftest ^{:stratum 1} claude-args-supervision-settings-test
  (testing "supervision settings path adds --settings flag"
    (let [args ((private-fn 'claude-args)
                {:prompt "p" :supervision {:settings-path "/tmp/s.json"}})]
      (is (some #(= "--settings" %) args))
      (is (some #(= "/tmp/s.json" %) args)))))

(deftest ^{:stratum 1} claude-args-model-test
  (testing "model adds --model flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :model "claude-sonnet-4-6"})]
      (is (some #(= "--model" %) args))
      (is (some #(= "claude-sonnet-4-6" %) args)))))

(deftest ^{:stratum 1} claude-args-resume-test
  (testing "resume adds --resume flag"
    (let [args ((private-fn 'claude-args) {:prompt "p" :resume "session-abc"})]
      (is (some #(= "--resume" %) args))
      (is (some #(= "session-abc" %) args)))))

(deftest ^{:stratum 1} claude-args-prompt-always-last-test
  (testing "prompt is always the last argument"
    (let [args ((private-fn 'claude-args)
                {:prompt "the-prompt" :streaming? true :system "sys"
                 :max-turns 5 :budget-usd 1.0 :model "claude-sonnet-4-6"})]
      (is (= "the-prompt" (last args))))))

;; ============================================================================
;; codex-args
;; ============================================================================
(deftest ^{:stratum 1} codex-args-minimal-test
  (testing "minimal prompt produces exec with explicit sandbox + approval flags"
    (let [args ((private-fn 'codex-args) {:prompt "fix bug"
                                          :prompt-via :argv})]
      (is (= "exec" (first args)))
      (is (some #(= "--json" %) args))
      ;; Explicit sandbox + approval-policy config replaces the deprecated
      ;; --full-auto alias.
      (is (some #(= "--ignore-user-config" %) args))
      (is (some #(= "--sandbox=workspace-write" %) args))
      (is (some #(re-matches #"approval_policy=\"?never\"?" %) args))
      (is (some #(= "--skip-git-repo-check" %) args))
      (is (= "fix bug" (last args))))))

(deftest ^{:stratum 1} codex-args-defaults-to-stdin-test
  (testing "default prompt delivery follows the backend stdin shape"
    (let [args ((private-fn 'codex-args) {:prompt "fix bug"})]
      (is (not (some #{"fix bug"} args)))
      (is (= "-" (last args))))))

(deftest ^{:stratum 1} codex-args-stdin-test
  (testing "stdin prompt delivery keeps prompt content out of argv"
    (let [args ((private-fn 'codex-args)
                {:prompt "fix bug" :prompt-via :stdin})]
      (is (not (some #{"fix bug"} args)))
      (is (= "-" (last args))))))

(deftest ^{:stratum 1} codex-args-preflight-safe-config-test
  (testing "config overrides are safe before artifact MCP config exists"
    (let [args ((private-fn 'codex-args) {:prompt "p"})]
      ;; The artifact MCP config writer marks the server required. Passing
      ;; only mcp_servers.artifact.required=true here creates an incomplete
      ;; server table and current Codex rejects it during generic preflight.
      (is (not (some #(= "mcp_servers.artifact.required=true" %) args)))
      ;; approval_policy=never is set via -c so any config.toml default
      ;; cannot relax it.
      (is (some #(re-matches #"approval_policy=\"?never\"?" %) args)))))

(deftest ^{:stratum 1} codex-args-model-test
  (testing "model adds -m flag"
    (let [args ((private-fn 'codex-args) {:prompt "p" :model "gpt-4o"})]
      (is (some #(= "-m" %) args))
      (is (some #(= "gpt-4o" %) args)))))

(deftest ^{:stratum 1} codex-args-system-test
  (testing "system prompt adds -c flag with JSON-encoded value"
    (let [args ((private-fn 'codex-args) {:prompt "p" :system "be helpful"})]
      (is (some #(str/starts-with? % "system_prompt=") args)))))

;; ============================================================================
;; cursor-args
;; ============================================================================
(deftest ^{:stratum 1} cursor-args-minimal-test
  (testing "minimal prompt produces [-p --force <prompt>] (autonomous writes)"
    (let [args ((private-fn 'cursor-args) {:prompt "fix it"})]
      (is (= ["-p" "--force" "fix it"] args)))))

(deftest ^{:stratum 1} cursor-args-no-approve-mcps-test
  (testing "MCP scoping is via the permissions allowlist, not --approve-mcps"
    (let [args ((private-fn 'cursor-args) {:prompt "p" :mcp-allowed-tools ["t1"]})]
      (is (not (some #(= "--approve-mcps" %) args)))
      (is (= "p" (last args))))))

(deftest ^{:stratum 1} cursor-args-model-test
  (testing "model adds --model <model> before the prompt"
    (let [args ((private-fn 'cursor-args) {:prompt "p" :model "gpt-5.2"})]
      (is (= ["-p" "--force" "--model" "gpt-5.2" "p"] args)))))

(deftest ^{:stratum 1} cursor-args-system-test
  (testing "system is prepended to the prompt (no system-prompt flag on cursor)"
    (let [args ((private-fn 'cursor-args) {:prompt "do it" :system "be terse"})]
      (is (= ["-p" "--force" "be terse\n\ndo it"] args)))))

;; ============================================================================
;; opencode-args
;; ============================================================================
(deftest ^{:stratum 1} opencode-args-minimal-test
  (testing "every call runs non-interactively, without external plugins, as the policy agent"
    (let [args ((private-fn 'opencode-args) {:prompt "explain"})]
      (is (= ["run" "--pure" "--agent"] (take 3 args)))
      (is (re-matches #"miniforge-[0-9a-f]{8}" (nth args 3)))
      (is (= ["explain"] (drop 4 args))))))

(deftest ^{:stratum 1} opencode-args-model-and-files-test
  (testing "the model and attached files pass through; the request's own agent is not used"
    (let [args ((private-fn 'opencode-args)
                (merge supervised {:prompt "fix it" :model "anthropic/claude-sonnet-4-5"
                                   :agent "build" :files ["README.md" "src/core.clj"]}))]
      (is (= ["--model" "anthropic/claude-sonnet-4-5" "--file" "README.md" "--file" "src/core.clj" "fix it"]
             (drop 4 args)))
      (is (not (some #{"build"} args))))))

(deftest ^{:stratum 1} opencode-args-refuses-server-command-and-resume-test
  (testing "no call may attach, run a configured command, or resume a session, in a session or not"
    (doseq [request [{:prompt "p"} (merge supervised {:prompt "p"})]
            option  [{:attach "http://localhost:4096"} {:command "review"} {:session "ses_1"}
                     {:continue? true} {:fork? true}]]
      (is (thrown? clojure.lang.ExceptionInfo ((private-fn 'opencode-args) (merge request option)))
          (pr-str option)))))

(deftest ^{:stratum 1} opencode-env-sets-the-calls-permissions-test
  (let [agent     (nth ((private-fn 'opencode-args) {:prompt "p"}) 3)
        config    (fn [request] (json/parse-string (get (cli-args/opencode-env request) "OPENCODE_CONFIG_CONTENT")))
        all-deny? (fn [block] (every? #(if (map? %) (every? #{"deny"} (vals %)) (= "deny" %)) (vals block)))]
    (testing "outside a session, isolated, and in a session that carries no block: everything denied"
      (doseq [request [{:prompt "p"} (merge supervised {:isolated? true}) supervised]]
        (is (all-deny? (get (config request) "permission")) (pr-str request))
        (is (all-deny? (get-in (config request) ["agent" agent "permission"])) (pr-str request))
        (is (= "primary" (get-in (config request) ["agent" agent "mode"])))))
    (testing "external_directory also denies OpenCode's tool-output directory by its exact glob"
      (with-redefs [settings/getenv-value (fn [k] (get {"HOME" "/home/op"} k))]
        (doseq [request [{:prompt "p"} (assoc-in supervised [:supervision :opencode-permission]
                                                 (array-map "*" "deny" "external_directory" "deny" "read" "allow"))]]
          (is (= {"*" "deny" "/home/op/.local/share/opencode/tool-output/*" "deny"}
                 (get-in (config request) ["agent" agent "permission" "external_directory"]))
              (pr-str request))))
      (doseq [data ["/data" "/data/" "/data//"]]
        (with-redefs [settings/getenv-value (fn [k] (get {"HOME" "/home/op" "XDG_DATA_HOME" data} k))]
          (is (contains? (get-in (config {:prompt "p"}) ["agent" agent "permission" "external_directory"])
                         "/data/opencode/tool-output/*")
              (str "XDG_DATA_HOME=" data " gives OpenCode's own spelling of the glob"))))
      (with-redefs [settings/getenv-value (fn [k] (get {"HOME" "/home/op"} k))]
        (is (contains? (cli-args/external-directory-rule "\\") "/home/op/.local/share/opencode/tool-output\\*")
            "the wildcard follows the platform separator, as path.join puts it")))
    (testing "plugins and the worktree's own OpenCode config stay off"
      (is (= "1" (get (cli-args/opencode-env supervised) "OPENCODE_PURE")))
      (is (= "1" (get (cli-args/opencode-env supervised) "OPENCODE_DISABLE_PROJECT_CONFIG"))))
    (testing "a session call's role block reaches the agent in rule order, at the size real blocks have"
      (let [commands (map #(str "cmd-" (char (+ 97 %)) " *") (range 26))
            bash     (apply array-map "*" "allow" (mapcat (fn [c] [c "deny"]) commands))
            block    (array-map "*" "deny" "external_directory" "deny" "read" "allow" "list" "allow"
                                "grep" "allow" "glob" "allow" "todowrite" "allow"
                                "edit" (array-map "*" "allow" ".git" "deny") "bash" bash)
            content  (get (cli-args/opencode-env (assoc-in supervised [:supervision :opencode-permission] block))
                          "OPENCODE_CONFIG_CONTENT")]
        (is (str/includes? content "{\"*\":\"deny\",\"external_directory\":{\"*\":\"deny\","))
        (is (str/includes? content "\"read\":\"allow\",\"list\":\"allow\",\"grep\":\"allow\",\"glob\":\"allow\","))
        (is (str/includes? content (str "\"bash\":{\"*\":\"allow\","
                                        (str/join "," (map #(str "\"" % "\":\"deny\"") commands)) "}"))
            "a 27-key rule object keeps its order")))
    (testing "the operator's own inline config is kept; only its permissions are replaced"
      (with-redefs [settings/getenv-value (fn [k] (when (= "OPENCODE_CONFIG_CONTENT" k)
                                                    "{\"model\":\"openrouter/m\",\"permission\":{\"bash\":\"allow\"},\"agent\":{\"mine\":{\"mode\":\"primary\"}}}"))]
        (let [c (config {:prompt "p"})]
          (is (= "openrouter/m" (get c "model")))
          (is (= "deny" (get-in c ["permission" "bash"])))
          (is (contains? (get c "agent") "mine")))))
    (testing "an operator inline config that is not plain JSON fails rather than being dropped"
      (with-redefs [settings/getenv-value (fn [k] (when (= "OPENCODE_CONFIG_CONTENT" k) "{\"model\": \"m\", // jsonc\n}"))]
        (is (thrown? clojure.lang.ExceptionInfo (cli-args/opencode-env {:prompt "p"})))))))

;; ============================================================================
;; echo-args
;; ============================================================================
(deftest ^{:stratum 1} echo-args-test
  (testing "echo backend returns prompt in a vector"
    (let [args ((private-fn 'echo-args) {:prompt "test-echo"})]
      (is (= ["test-echo"] args)))))
