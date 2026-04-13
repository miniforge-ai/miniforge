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
   [babashka.process :as process]
   [cheshire.core :as json]
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

;------------------------------------------------------------------------------ Layer 2
;; fleet watch — TUI fleet monitor

(defn fleet-watch-cmd
  "Launch the TUI fleet monitor.

   Opens the two-pane fleet dashboard showing repos, PRs, and live
   workflow status across all configured repos.

   Delegates to ai.miniforge.tui-views.interface/start-fleet-tui! when
   available. Falls back to the standalone TUI when not."
  [opts]
  (display/print-info "Starting fleet TUI monitor...")
  (display/print-info (str "Events dir: " (app-config/events-dir)))
  (let [fleet-tui! (try (requiring-resolve 'ai.miniforge.tui-views.interface/start-fleet-tui!)
                        (catch Exception _ nil))
        standalone! (try (requiring-resolve 'ai.miniforge.tui-views.interface/start-standalone-tui!)
                         (catch Exception _ nil))]
    (cond
      fleet-tui!
      (fleet-tui! opts)

      standalone!
      (do
        (display/print-info "  (fleet-tui not available, using standalone TUI)")
        (standalone! opts))

      :else
      (do
        (display/print-error "TUI not available in this runtime.")
        (println)
        (println (str "  Start the web dashboard instead: "
                      (app-config/command-string "web")))
        (System/exit 1)))))

;------------------------------------------------------------------------------ Layer 2
;; fleet prs — PRs across fleet

(defn- fetch-prs-for-repo [repo]
  (let [result (process/sh "gh" "pr" "list" "--repo" repo
                            "--json" "number,title,state,author,createdAt,additions,deletions,changedFiles"
                            "--limit" "20")]
    (when (zero? (:exit result))
      (try (json/parse-string (:out result) true)
           (catch Exception _ [])))))

(defn fleet-prs-cmd
  "List open PRs across all fleet-configured repositories.

   Equivalent to running `pr list` for every repo in [:fleet :repos].
   Prints a grouped, risk-annotated summary."
  [opts default-config-path default-config]
  (let [config (load-config (:config opts) default-config-path default-config)
        repos  (if-let [r (:repo opts)]
                 [r]
                 (get-in config [:fleet :repos] []))]
    (if (empty? repos)
      (do
        (display/print-error "No repos configured.")
        (println (str "  Add repos with: " (app-config/command-string "fleet add <repo>"))))
      (do
        (println)
        (println (display/style "Fleet PRs" :foreground :cyan :bold true))
        (println)
        (doseq [repo repos]
          (println (display/style (str "  " repo) :foreground :cyan :bold true))
          (let [prs (fetch-prs-for-repo repo)]
            (cond
              (nil? prs)
              (println (display/style "    Could not reach repo (check gh auth)" :foreground :red))

              (empty? prs)
              (println "    No open PRs.")

              :else
              (doseq [{:keys [number title author additions deletions changedFiles]} prs]
                (let [total   (+ (or additions 0) (or deletions 0))
                      files   (or changedFiles 0)
                      risk    (cond
                                (or (> total 500) (> files 20)) :high
                                (or (> total 100) (> files 5))  :medium
                                :else                            :low)
                      risk-color (case risk :high :red :medium :yellow :low :green)]
                  (println (str "    "
                                (display/style (case risk :high "[HIGH]" :medium "[MED]" "[LOW]")
                                               :foreground risk-color)
                                " #" number
                                " " (if (> (count title) 55) (str (subs title 0 54) "...") title)
                                "  (" (get author :login "?") ")"))))))
          (println))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (fleet-watch-cmd {})
  :end)
