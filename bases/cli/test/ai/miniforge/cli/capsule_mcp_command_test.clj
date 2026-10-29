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
(ns ai.miniforge.cli.capsule-mcp-command-test
  "The commands a capsule artifact session writes into the capsule run
   `bb miniforge <subcommand>`, i.e. this CLI. Each subcommand must be one
   the CLI dispatches, or the MCP server / hook fails to start with
   \"Unknown command\" and the agent runs without them."
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.artifact-session :as session]
   [ai.miniforge.cli.main :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capsule-commands
  "Run write-capsule-mcp-config! with a recording exec!. Returns the MCP
   server's {\"command\" \"args\"} from the written config, and the hook
   command."
  []
  (let [scripts (atom [])
        exec!   (fn [_executor _env-id script _opts] (swap! scripts conj script) {:exit-code 0})
        result  (session/write-capsule-mcp-config!
                 {:exec!           exec!
                  :executor        :capsule
                  :environment-id  "env-1"
                  :dir             "/tmp/session"
                  :mcp-config-path "/tmp/session/mcp-config.json"
                  :workdir         "/workspace"})
        config  (->> (first @scripts)
                     (re-find #"(?s)<< 'MCPEOF'\n(.*)\nMCPEOF")
                     second
                     json/parse-string)]
    {:server (get-in config ["mcpServers" "context"])
     :hook   (get-in result [:supervision :hook-eval-cmd])}))

(defn- ^{:stratum 0} cli-command?
  [subcommand]
  (contains? (set (map :cmds sut/dispatch-table)) [subcommand]))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} capsule-mcp-server-command-resolves-test
  (testing "the MCP server runs `bb miniforge` with a subcommand the CLI dispatches"
    (let [{:keys [server]} (capsule-commands)
          [task subcommand] (get server "args")]
      (is (= "bb" (get server "command")))
      (is (= "miniforge" task))
      (is (cli-command? subcommand) (str subcommand " is not a CLI command")))))

(deftest ^{:stratum 1} capsule-hook-command-resolves-test
  (testing "the PreToolUse hook runs `bb miniforge` with a subcommand the CLI dispatches"
    (let [[bb task subcommand] (re-seq #"\S+" (:hook (capsule-commands)))]
      (is (= ["bb" "miniforge"] [bb task]))
      (is (cli-command? subcommand)))))
