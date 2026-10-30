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
(ns ai.miniforge.llm.agent-env-test
  "An agent CLI process gets an allowlisted environment: the basics,
   proxies and provider keys pass; credentials miniforge itself holds do
   not."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.llm.protocols.impl.llm-client.stream-exec :as stream-exec]
   [ai.miniforge.llm.protocols.impl.llm-client.subprocess :as subprocess]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private operator-env
  "An operator environment holding credentials the agent must not see."
  {"PATH" (System/getenv "PATH") "HOME" "/home/op" "LANG" "en_US.UTF-8" "TERM" "xterm"
   "LC_ALL" "en_US.UTF-8" "HTTPS_PROXY" "http://proxy.example:3128"
   "ANTHROPIC_API_KEY" "sk-ant" "OPENAI_API_KEY" "sk-oai" "CLAUDE_CODE_OAUTH_TOKEN" "oauth"
   "MINIFORGE_HOME" "/home/op/.miniforge"
   "GH_TOKEN" "ghp" "GITHUB_TOKEN" "ghs" "AWS_ACCESS_KEY_ID" "AKIA" "AWS_SECRET_ACCESS_KEY" "s"
   "AWS_SESSION_TOKEN" "t" "SSH_AUTH_SOCK" "/tmp/ssh.sock" "MINIFORGE_GIT_TOKEN" "g"
   "MINIFORGE_FLEET_API_KEY" "f" "MINIFORGE_WEB_PASSWORD" "p" "CLAUDECODE" "1"})

(def ^{:stratum 0} ^:private kept-names
  "The `operator-env` names the shipped allowlist keeps."
  #{"PATH" "HOME" "LANG" "TERM" "LC_ALL" "HTTPS_PROXY" "ANTHROPIC_API_KEY"
    "OPENAI_API_KEY" "CLAUDE_CODE_OAUTH_TOKEN" "MINIFORGE_HOME"})

(def ^{:stratum 0} ^:private withheld-names
  ["GH_TOKEN" "GITHUB_TOKEN" "AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_SESSION_TOKEN"
   "SSH_AUTH_SOCK" "MINIFORGE_GIT_TOKEN" "MINIFORGE_FLEET_API_KEY" "MINIFORGE_WEB_PASSWORD"
   "CLAUDECODE"])

(defn- ^{:stratum 0} env-names
  "The variable names in `env` command output."
  [out]
  (set (keep #(first (str/split % #"=" 2)) (str/split-lines (str out)))))

(deftest ^{:stratum 0} deny-prefix-wins-test
  (testing "a name matching a deny prefix is dropped even when an allow entry matches it"
    (is (= {"PATH" "/bin"}
           (subprocess/allowed-env {:allow ["PATH" "GH_TOKEN"]
                                    :allow-prefixes ["AWS_"]
                                    :deny-prefixes ["AWS_" "GH_"]}
                                   {"PATH" "/bin" "GH_TOKEN" "x" "AWS_REGION" "us-east-1"})))))

(deftest ^{:stratum 0} names-match-without-case-test
  (testing "Windows' Path is kept, and a lower-case name still meets the deny prefixes"
    (is (= {"Path" "C:\\Windows"}
           (subprocess/allowed-env {:allow ["PATH"] :allow-prefixes ["GH"] :deny-prefixes ["GH_"]}
                                   {"Path" "C:\\Windows" "gh_token" "x"})))))

(deftest ^{:stratum 0} opencode-call-carries-its-deny-config-test
  (testing "an OpenCode call outside a session reaches exec with --pure and the deny-all config"
    (let [calls  (atom [])
          record (fn [cmd opts] (swap! calls conj [cmd opts]) {:out "ok" :err "" :exit 0})
          client (llm/create-client {:backend :opencode
                                     :exec-fn (fn ([cmd] (record cmd nil)) ([cmd opts] (record cmd opts)))})]
      (llm/complete client {:prompt "hi"})
      (let [[cmd opts] (first @calls)]
        (is (some #{"--pure"} cmd))
        (is (contains? (:env opts) "OPENCODE_CONFIG_CONTENT"))))))

(deftest ^{:stratum 0} capsule-exec-carries-env-entries-test
  (testing "a governed call's env entries reach the capsule command"
    (let [seen (atom nil)
          exec (impl/capsule-exec-fn (fn [_executor _env-id cmd opts] (reset! seen (assoc opts :cmd cmd)) {:data {:stdout "ok"}})
                                     :executor "env-1" "/workspace")]
      (is (= "ok" (:out (exec ["opencode" "run" "p"] {:env {"OPENCODE_CONFIG_CONTENT" "{}" "OPENCODE_PURE" "1"}}))))
      (is (= ["env" "OPENCODE_CONFIG_CONTENT={}" "OPENCODE_PURE=1" "opencode" "run" "p"] (:cmd @seen))
          "as an env prefix, which every executor runs, not only one that honours :env")
      (is (= "/workspace" (:workdir @seen))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} backend-env-entries-reach-the-process-test
  (testing "env entries a backend adds are set on top of the agent environment"
    (with-redefs [subprocess/host-env (constantly operator-env)]
      (let [names (env-names (:out (impl/default-exec-fn ["env"] {:env {"OPENCODE_CONFIG_CONTENT" "{}"}})))]
        (is (contains? names "OPENCODE_CONFIG_CONTENT"))
        (is (contains? names "ANTHROPIC_API_KEY"))
        (is (not-any? names withheld-names))))))

(deftest ^{:stratum 1} agent-env-keeps-only-allowed-names-test
  (testing "the shipped allowlist keeps the basics and provider keys and drops miniforge's credentials"
    (with-redefs [subprocess/host-env (constantly operator-env)]
      (is (= kept-names (set (keys (subprocess/agent-env))))))))

(deftest ^{:stratum 1} operator-can-add-names-test
  (testing "MINIFORGE_AGENT_ENV_ALLOW adds names, but cannot let a denied prefix through"
    (with-redefs [subprocess/host-env (constantly (assoc operator-env
                                                         "MINIFORGE_AGENT_ENV_ALLOW" " SOME_PROVIDER_KEY ,GH_TOKEN"
                                                         "SOME_PROVIDER_KEY" "k"))]
      (let [env (subprocess/agent-env)]
        (is (= "k" (get env "SOME_PROVIDER_KEY")))
        (is (not (contains? env "GH_TOKEN")))))))

(deftest ^{:stratum 1} agent-cli-processes-see-only-allowed-env-test
  (with-redefs [subprocess/host-env (constantly operator-env)]
    (testing "a non-streaming agent CLI call"
      (let [names (env-names (:out (impl/default-exec-fn ["env"])))]
        (is (contains? names "ANTHROPIC_API_KEY"))
        (is (not-any? names withheld-names))))
    (testing "a streaming agent CLI call"
      (let [lines (atom [])]
        (stream-exec/stream-exec-fn ["env"] #(swap! lines conj %) {})
        (let [names (env-names (str/join "\n" @lines))]
          (is (contains? names "ANTHROPIC_API_KEY"))
          (is (not-any? names withheld-names)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (subprocess/agent-env)
  :end)
