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
(ns ai.miniforge.cli.observability.timeline
  "Per-workflow event-timeline rendering ('mf events show <workflow-id>'):
   transit-json event-file reading, timestamp/summary rendering, and the
   `show-events` command itself. Split out of `ai.miniforge.cli.observability`
   (rule 210: the combined namespace measured 7 real layers, max 3) — same
   approach as the policy-pack loader split, miniforge#1772, and detection
   split, miniforge#1761/#1773.

   Layer 0: per-workflow event-directory path, transit-prefix stripping,
     timestamp/event-summary rendering — pure, no same-file dependents
   Layer 1: read-workflow-events (over Layer 0)
   Layer 2: show-events (over Layer 0 and Layer 1)"
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.observability.formatting :as formatting]))

;------------------------------------------------------------------------------ Layer 0

;; Workflow timeline (show)
(defn- ^{:stratum 0} workflow-events-dir
  "Path to the per-workflow event directory for a given workflow id."
  [workflow-id]
  (io/file (app-config/events-dir) (str workflow-id)))

(defn- ^{:stratum 0} strip-transit-prefix
  "Transit-json keys arrive as '~:foo/bar'. Strip the prefix so our code
   can use keyword accessors. Recursive walk over maps + vectors."
  [x]
  (cond
    (map? x)
    (reduce-kv (fn [acc k v]
                 (let [k' (if (and (string? k) (.startsWith ^String k "~:"))
                            (keyword (subs k 2))
                            k)]
                   (assoc acc k' (strip-transit-prefix v))))
               {}
               x)

    (vector? x)
    (mapv strip-transit-prefix x)

    (and (string? x) (.startsWith ^String x "~:"))
    (keyword (subs x 2))

    (and (string? x) (.startsWith ^String x "~t"))
    (subs x 2)

    (and (string? x) (.startsWith ^String x "~u"))
    (subs x 2)

    :else x))

(defn- ^{:stratum 0} ts-short
  "Render the :event/timestamp field (may be a plain string after transit
   stripping) as HH:MM:SS."
  [ts]
  (let [s (str ts)]
    (cond
      (re-find #"\d\d:\d\d:\d\d" s)
      (first (re-find #"(\d\d:\d\d:\d\d)" s))
      :else
      (subs s 0 (min 8 (count s))))))

(defn- ^{:stratum 0} summarize-event
  "One-line summary for a single event. Emphasizes tool names, phase
   outcomes, DAG decisions."
  [ev]
  (let [t (:event/type ev)
        phase (:workflow/phase ev)
        tool (:tool/name ev)
        tool-names (:tool/names ev)
        dag-outcome (:dag/outcome ev)
        dag-reason (:dag/reason ev)
        outcome (:phase/outcome ev)
        duration (:phase/duration-ms ev)
        err (or (:phase/error ev)
                (get-in ev [:dag/diagnostic :result/error :error/message]))]
    (cond
      (= :workflow/phase-started t)
      (str "→ " (some-> phase name) " started")

      (= :workflow/phase-completed t)
      (str "✓ " (some-> phase name) " " (some-> outcome name)
           (when duration (format " (%.1fs)" (/ duration 1000.0)))
           (when err (str " — " (subs (str err) 0 (min 160 (count (str err)))))))

      (= :agent/tool-call t)
      (str "  • tool " (or tool
                           (when (seq tool-names) (str/join "," (map str tool-names)))
                           "(unnamed)"))

      (= :agent/status t)
      (str "  · " (or (:status/type ev) "status") " — " (:message ev ""))

      (= :agent/chunk t)
      (str "  … chunk "
           (if (:chunk/done? ev) "done" "streaming"))

      (= :workflow/dag-considered t)
      (str "⇒ DAG " (some-> dag-outcome name)
           (when dag-reason (str " — " (name dag-reason))))

      (= :workflow/started t) "▶ workflow started"
      (= :workflow/completed t) (str "■ workflow completed — " (some-> (:workflow/status ev) name))
      (= :workflow/failed t) (str "✗ workflow failed — " (:workflow/failure-reason ev ""))

      :else (str (some-> t name)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-workflow-events
  "Read + parse every .json event file under the workflow directory, sorted
   by filename (which is timestamp-prefixed)."
  [dir]
  (when (.exists ^java.io.File dir)
    (->> (.listFiles ^java.io.File dir)
         (filter #(.endsWith (.getName ^java.io.File %) ".json"))
         (sort-by #(.getName ^java.io.File %))
         (keep (fn [f]
                 (try
                   (let [raw (json/parse-string (slurp f) false)]
                     (strip-transit-prefix raw))
                   (catch Exception _e nil)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} show-events
  "Render a human-readable timeline for a specific workflow.

   Output per line: HH:MM:SS  summary. Filters are available via opts.

   Opts:
     :filter     — keyword event-type to include (default: show all)
     :no-chunks  — drop :agent/chunk events (default: true; too noisy)
     :no-status  — drop :agent/status events (default: false)"
  [{:keys [workflow-id filter no-chunks no-status]
    :or {no-chunks true no-status false}}]
  (if-not workflow-id
    (do (println (formatting/colorize :red "error: workflow-id is required"))
        (println "usage: mf events show <workflow-id>"))
    (let [dir (workflow-events-dir workflow-id)
          events (read-workflow-events dir)]
      (if (empty? events)
        (println (formatting/colorize :yellow (str "No events found for workflow " workflow-id
                                        " (looked in " (.getPath dir) ")")))
        (let [kept (cond->> events
                     filter     (clojure.core/filter #(= filter (:event/type %)))
                     no-chunks  (remove #(= :agent/chunk (:event/type %)))
                     no-status  (remove #(= :agent/status (:event/type %))))]
          (println (formatting/colorize :cyan (str "Timeline for workflow " workflow-id
                                        " — " (count kept) " event(s)")))
          (println (formatting/colorize :gray (apply str (repeat 80 "─"))))
          (doseq [ev kept]
            (println (formatting/colorize :gray (ts-short (:event/timestamp ev)))
                     (summarize-event ev))))))))
