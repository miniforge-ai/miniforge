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
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.artifact-session :as session]))

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
      :workdir         "/workspace"})
    (->> @scripts
         (some #(second (re-find #"(?s)<< 'SETTINGSEOF'\n(.*)\nSETTINGSEOF" %)))
         json/parse-string)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} settings-problems
  "Problems with a settings map's PreToolUse hooks, or [] when conforming."
  [settings]
  (let [groups (get-in settings ["hooks" "PreToolUse"])]
    (if (and (sequential? groups) (seq groups))
      (vec (mapcat matcher-group-problems groups))
      ["no PreToolUse array"])))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} host-settings-have-the-documented-hook-shape-test
  (testing "write-claude-settings! (host sessions)"
    (let [s (session/create-session!)]
      (try
        (let [settings (json/parse-string (slurp (session/write-claude-settings! (:dir s))))]
          (is (= [] (settings-problems settings)))
          (is (str/ends-with? (str (get-in settings ["hooks" "PreToolUse" 0 "hooks" 0 "command"]))
                              "hook-eval")))
        (finally
          (session/cleanup-session! s))))))

(deftest ^{:stratum 2} capsule-settings-have-the-documented-hook-shape-test
  (testing "write-capsule-mcp-config! (capsule sessions)"
    (let [settings (capsule-settings)]
      (is (= [] (settings-problems settings)))
      (is (= "bb miniforge hook-eval"
             (get-in settings ["hooks" "PreToolUse" 0 "hooks" 0 "command"]))))))
