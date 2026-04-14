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
   [ai.miniforge.cli.main.commands.shared :as shared]
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

(def ^:private fleet-status-spec
  {:header :fleet/status-header
   :fields [[:fleet/repositories     :fleet/repositories      {:param :count}]
            [:fleet/active-workflows :fleet/active-workflows  {:param :count}]
            [:fleet/pending-workflows :fleet/pending-workflows {:param :count}]
            [:fleet/completed        :fleet/completed          {:param :count}]
            [:fleet/failed           :fleet/failed             {:param :count}]]})

(defn fleet-status-cmd
  [opts default-config-path default-config]
  (let [config (load-config (:config opts) default-config-path default-config)
        repos (get-in config [:fleet :repos] [])
        state-file (app-config/state-file)
        state (if (fs/exists? state-file)
                (edn/read-string (slurp state-file))
                {:workflows {:active 0 :pending 0 :completed 0 :failed 0}})]
    (display/render-detail
     fleet-status-spec
     {:fleet/repositories     (count repos)
      :fleet/active-workflows (get-in state [:workflows :active] 0)
      :fleet/pending-workflows (get-in state [:workflows :pending] 0)
      :fleet/completed        (get-in state [:workflows :completed] 0)
      :fleet/failed           (get-in state [:workflows :failed] 0)})))

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
  (display/print-info (messages/t :fleet/watch-starting))
  (display/print-info (messages/t :fleet/watch-events-dir {:dir (app-config/events-dir)}))
  (let [fleet-tui!  (shared/try-resolve 'ai.miniforge.tui-views.interface/start-fleet-tui!)
        standalone! (shared/try-resolve 'ai.miniforge.tui-views.interface/start-standalone-tui!)]
    (cond
      fleet-tui!
      (fleet-tui! opts)

      standalone!
      (do
        (display/print-info (messages/t :fleet/watch-fallback))
        (standalone! opts))

      :else
      (do
        (display/print-error (messages/t :fleet/watch-unavailable))
        (println)
        (println (messages/t :fleet/watch-use-web
                            {:command (app-config/command-string "web")}))
        (shared/exit! 1)))))

;------------------------------------------------------------------------------ Layer 2
;; fleet prs — PRs across fleet

(defn- fetch-prs-for-repo [repo]
  (let [result (process/sh "gh" "pr" "list" "--repo" repo
                            "--json" "number,title,state,author,createdAt,additions,deletions,changedFiles"
                            "--limit" (str shared/max-prs-per-repo))]
    (when (zero? (:exit result))
      (try (json/parse-string (:out result) true)
           (catch Exception _ [])))))

(defn- pr-risk-level
  "Classify PR risk from total changed lines and changed file count."
  [total-lines changed-files]
  (cond
    (or (> total-lines shared/pr-risk-lines-high)
        (> changed-files shared/pr-risk-files-high))   :high
    (or (> total-lines shared/pr-risk-lines-medium)
        (> changed-files shared/pr-risk-files-medium)) :medium
    :else                                               :low))

(defn- display-pr-row
  "Print a single PR row with risk annotation."
  [{:keys [number title author additions deletions changedFiles]}]
  (let [total       (+ (or additions 0) (or deletions 0))
        files       (or changedFiles 0)
        risk        (pr-risk-level total files)
        risk-color  (case risk :high :red :medium :yellow :low :green)
        risk-label  (case risk
                      :high   (messages/t :fleet/prs-risk-high)
                      :medium (messages/t :fleet/prs-risk-medium)
                      (messages/t :fleet/prs-risk-low))
        max-title   55
        short-title (if (> (count title) max-title)
                      (str (subs title 0 (dec max-title)) "...")
                      title)]
    (println (str "    "
                  (display/style risk-label :foreground risk-color)
                  " #" number
                  " " short-title
                  "  (" (get author :login "?") ")"))))

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
        (display/print-error (messages/t :fleet/prs-no-repos))
        (println (messages/t :fleet/prs-add-hint
                            {:command (app-config/command-string "fleet add <repo>")})))
      (do
        (println)
        (println (display/style (messages/t :fleet/prs-header) :foreground :cyan :bold true))
        (println)
        (doseq [repo repos]
          (println (display/style (str "  " repo) :foreground :cyan :bold true))
          (let [prs (fetch-prs-for-repo repo)]
            (cond
              (nil? prs)
              (println (display/style (messages/t :fleet/prs-unreachable) :foreground :red))

              (empty? prs)
              (println (messages/t :fleet/prs-none))

              :else
              (doseq [pr-data prs]
                (display-pr-row pr-data))))
          (println))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (fleet-watch-cmd {})
  :end)
