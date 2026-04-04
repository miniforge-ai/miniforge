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

(ns ai.miniforge.cli.main.commands.fleet
  "Fleet management commands."
  (:require
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Config helpers

(defn load-config
  "Load configuration from file, merging with defaults."
  [path default-config-path default-config]
  (let [config-path (or path default-config-path)]
    (if (fs/exists? config-path)
      (merge-with merge default-config (edn/read-string (slurp config-path)))
      default-config)))

(defn save-config
  "Save configuration to file."
  [config path default-config-path]
  (let [config-path (or path default-config-path)]
    (fs/create-dirs (fs/parent config-path))
    (spit config-path (pr-str config))))

;------------------------------------------------------------------------------ Layer 1
;; Fleet commands

(defn fleet-start-cmd
  [_opts]
  (display/print-info (messages/t :fleet/starting-daemon))
  (println (messages/t :fleet/daemon-todo)))

(defn fleet-stop-cmd
  [_opts]
  (display/print-info (messages/t :fleet/stopping-daemon))
  (println (messages/t :fleet/daemon-todo)))

(defn fleet-status-cmd
  [opts default-config-path default-config]
  (let [config (load-config (:config opts) default-config-path default-config)
        repos (get-in config [:fleet :repos] [])
        state-file (app-config/state-file)
        state (if (fs/exists? state-file)
                (edn/read-string (slurp state-file))
                {:workflows {:active 0 :pending 0 :completed 0 :failed 0}})]

    (println)
    (println (display/style (messages/t :fleet/status-header) :foreground :cyan :bold true))
    (println)
    (println (messages/t :fleet/repositories {:count (count repos)}))
    (println (messages/t :fleet/active-workflows {:count (get-in state [:workflows :active] 0)}))
    (println (messages/t :fleet/pending-workflows {:count (get-in state [:workflows :pending] 0)}))
    (println (messages/t :fleet/completed {:count (get-in state [:workflows :completed] 0)}))
    (println (messages/t :fleet/failed {:count (get-in state [:workflows :failed] 0)}))
    (println)))

(defn fleet-add-cmd
  [opts default-config-path default-config]
  (let [{:keys [repo config]} opts]
    (if-not repo
      (display/print-error (messages/t :fleet/add-usage {:command (app-config/command-string "fleet add <repo>")}))
      (let [cfg (load-config config default-config-path default-config)
            repos (get-in cfg [:fleet :repos] [])
            new-cfg (assoc-in cfg [:fleet :repos] (conj repos repo))]
        (save-config new-cfg config default-config-path)
        (display/print-success (messages/t :fleet/added {:repo repo}))))))

(defn fleet-remove-cmd
  [opts default-config-path default-config]
  (let [{:keys [repo config]} opts]
    (if-not repo
      (display/print-error (messages/t :fleet/remove-usage {:command (app-config/command-string "fleet remove <repo>")}))
      (let [cfg (load-config config default-config-path default-config)
            repos (get-in cfg [:fleet :repos] [])
            new-cfg (assoc-in cfg [:fleet :repos] (vec (remove #{repo} repos)))]
        (save-config new-cfg config default-config-path)
        (display/print-success (messages/t :fleet/removed {:repo repo}))))))
