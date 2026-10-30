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
(ns ai.miniforge.agent.claude-hook-settings-test
  "The Claude settings miniforge writes for tool supervision have the hook
   shape Claude Code documents (https://code.claude.com/docs/en/hooks and the
   settings JSON schema): PreToolUse is an array of matcher groups, each
   {\"matcher\" <optional string>, \"hooks\" [{\"type\" \"command\" \"command\" ...}]},
   with no other keys. A handler placed directly in the array is invalid, and
   Claude never runs it."
  (:require
   [cheshire.core :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.artifact-session :as session]
   [ai.miniforge.agent.artifact-session.cli-config :as cli-config]
   [ai.miniforge.agent.protocols.records.specialized :as specialized]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} matcher-group-problems
  "Problems with one PreToolUse entry against the documented hookMatcher
   shape; empty when it conforms."
  [group]
  (let [handlers (get group "hooks")]
    (cond-> []
      (not (map? group))
      (conj "entry is not an object")

      (seq (remove #{"matcher" "hooks"} (keys group)))
      (conj (str "keys outside matcher/hooks: " (vec (remove #{"matcher" "hooks"} (keys group)))))

      (and (contains? group "matcher") (not (string? (get group "matcher"))))
      (conj "matcher is not a string")

      (not (and (sequential? handlers) (seq handlers)))
      (conj "no hooks array")

      (some #(not (and (= "command" (get % "type"))
                       (string? (get % "command"))
                       (not (str/blank? (get % "command")))))
            handlers)
      (conj "a handler is not {type command, command <string>}"))))

(defn- ^{:stratum 0} capsule-settings
  "Run write-capsule-mcp-config! with a recording exec! and return the
   settings JSON it writes inside the capsule, parsed."
  []
  (let [scripts (atom [])
        exec!   (fn [_executor _env-id script _opts] (swap! scripts conj script) {:exit-code 0})]
    (session/write-capsule-mcp-config!
     {:exec!           exec!
      :executor        :capsule
      :environment-id  "env-1"
      :dir             "/tmp/session"
      :mcp-config-path "/tmp/session/mcp-config.json"
      :workdir         "/workspace"
      :role            :reviewer})
    (->> @scripts
         (some #(second (re-find #"(?s)<< 'SETTINGSEOF'\n(.*)\nSETTINGSEOF" %)))
         json/parse-string)))

(defn- ^{:stratum 0} settings-command
  "The PreToolUse hook command in the Claude settings file a host session
   wrote: the file `--settings` hands Claude."
  [session]
  (-> (get-in session [:supervision :settings-path])
      slurp
      json/parse-string
      (get-in ["hooks" "PreToolUse" 0 "hooks" 0 "command"])))

(defn- ^{:stratum 0} role-agent
  "An agent of `role` whose invoke-fn returns what `session-fn` returns
   when called with the context the agent passes it."
  [role session-fn]
  (specialized/create-base-agent
   {:role          role
    :system-prompt "test"
    :invoke-fn     (fn [context _input] (session-fn context))
    :validate-fn   (constantly {:valid? true})
    :repair-fn     (fn [output _errors _context] output)}))

(defn- ^{:stratum 0} capsule-context
  "A governed execution context whose exec! records each script."
  [scripts]
  {:execution/mode           :governed
   :execution/executor       :capsule
   :execution/environment-id "env-1"
   :execution/worktree-path  "/workspace"
   :execution/execute-fn     (fn [_executor _env-id script _opts]
                               (swap! scripts conj script)
                               {:exit-code 0 :data {:stdout ""}})})

(deftest ^{:stratum 0} hook-command-runs-under-sh-test
  (testing "a missing miniforge command exits 2, the code Claude reads as block, not 127"
    (let [cmd (session/hook-eval-command ["/nonexistent/miniforge-cmd"] :implementer "/work")]
      (is (= 2 (:exit (shell/sh "sh" "-c" cmd :in "{}"))))))
  (testing "a namespaced role keeps its namespace, so it does not pass as a listed role"
    (is (= "mf hook-eval --role custom/implementer || exit 2"
           (session/hook-eval-command ["mf"] :custom/implementer nil))))
  (testing "a miniforge command whose path has a space stays one word"
    (let [dir (java.nio.file.Files/createTempDirectory "hook dir " (make-array java.nio.file.attribute.FileAttribute 0))
          mf  (str dir "/mf")]
      (try
        (spit mf "#!/bin/sh\necho \"$@\"\n")
        (.setExecutable (java.io.File. mf) true)
        (let [cmd                (session/hook-eval-command [mf] :tester (str dir))
              {:keys [exit out]} (shell/sh "sh" "-c" cmd :in "{}")]
          (is (= 0 exit))
          (is (= (str "hook-eval --role tester --root " dir "\n") out)))
        (finally
          (run! #(.delete ^java.io.File %) (reverse (file-seq (.toFile dir)))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} agent-sessions-hand-the-role-to-the-hook-test
  (testing "host sessions opened in an agent's invoke-fn write that agent's role into the settings file"
    (doseq [[open-session role] [[session/with-session :tester]
                                 [session/with-readonly-session :reviewer]]]
      (let [agent (role-agent role #(open-session % settings-command))]
        (is (str/includes? (:llm-result ((:invoke-fn agent) {} "task"))
                           (str "hook-eval --role " (name role) " --root "))))))
  (testing "a capsule session writes the role into the settings it puts in the capsule"
    (let [scripts (atom [])
          agent   (role-agent :implementer #(session/with-session % (constantly nil)))]
      ((:invoke-fn agent) (capsule-context scripts) "task")
      (is (some #(str/includes? % "bb miniforge hook-eval --role implementer --root /workspace || exit 2")
                @scripts))))
  (testing "a session with no :agent/role has no --role, so hook-eval refuses every tool"
    (is (str/includes? (:llm-result (session/with-session {} settings-command))
                       "hook-eval --root "))))

(defn- ^{:stratum 1} settings-problems
  "Problems with a settings map's PreToolUse hooks, or [] when conforming."
  [settings]
  (let [groups (get-in settings ["hooks" "PreToolUse"])]
    (if (and (sequential? groups) (seq groups))
      (vec (mapcat matcher-group-problems groups))
      ["no PreToolUse array"])))

(deftest ^{:stratum 1} session-settings-allow-only-the-roles-tools-test
  (testing "a capsule reviewer session's settings allow the reviewer's tools and no write or shell tool"
    (let [allow (set (get-in (capsule-settings) ["permissions" "allow"]))]
      (is (contains? allow "mcp__context__context_read"))
      (is (not-any? allow ["Write" "Edit" "Bash" "mcp__context__context_write"]))))
  (testing "a host implementer session's settings file allows its write and shell tools"
    (let [dir  (str (java.nio.file.Files/createTempDirectory
                     "claude-settings" (make-array java.nio.file.attribute.FileAttribute 0)))
          path (cli-config/write-claude-settings! dir :implementer dir)
          allow (set (get-in (json/parse-string (slurp path)) ["permissions" "allow"]))]
      (is (every? allow ["Bash" "Write" "mcp__context__context_write"]))))
  (testing "the hook blocks the call when it times out or fails, rather than falling back to the allow rules"
    (is (= "block" (get-in (cli-config/pre-tool-use-settings "true" #{})
                           ["hooks" "PreToolUse" 0 "hooks" 0 "onFailure"]))))
  (testing "a role the policy does not list is allowed nothing"
    (is (= [] (get-in (cli-config/pre-tool-use-settings "true" (cli-config/role-allowed-tools :unlisted))
                      ["permissions" "allow"])))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} host-settings-have-the-documented-hook-shape-test
  (testing "write-claude-settings! (host sessions)"
    (let [s (session/create-session!)]
      (try
        (let [settings (json/parse-string (slurp (session/write-claude-settings! (:dir s) :implementer "/work")))]
          (is (= [] (settings-problems settings)))
          (is (str/ends-with? (str (get-in settings ["hooks" "PreToolUse" 0 "hooks" 0 "command"]))
                              "hook-eval --role implementer --root /work || exit 2")))
        (finally
          (session/cleanup-session! s))))))

(deftest ^{:stratum 2} capsule-settings-have-the-documented-hook-shape-test
  (testing "write-capsule-mcp-config! (capsule sessions)"
    (let [settings (capsule-settings)]
      (is (= [] (settings-problems settings)))
      (is (= "bb miniforge hook-eval --role reviewer --root /workspace || exit 2"
             (get-in settings ["hooks" "PreToolUse" 0 "hooks" 0 "command"]))))))
