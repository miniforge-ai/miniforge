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
(ns ai.miniforge.cli.main.commands.doctor
  "`mf doctor`: run the checks in `doctor.checks`, print one line per
   check, and exit 1 when a required check fails.

   Options mirror `mf run`: --backend checks that backend instead of the
   configured one, --execution-mode decides whether the container runtime
   is required."
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main.commands.doctor.checks :as checks]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-checks
  "Run every doctor check for `opts` (:backend, :execution-mode) and
   return the results in display order. The backend resolves the way a
   run resolves it: --backend, else the config (and its env override),
   else the default."
  [opts]
  (let [backend        (config/get-llm-backend (config/load-config) (:backend opts))
        execution-mode (get opts :execution-mode :local)
        command-checks (mapv checks/command-check (checks/command-check-specs backend))]
    (conj command-checks
          (checks/runtime-check execution-mode)
          (checks/config-check config/default-user-config-path))))

(defn ^{:stratum 0} failed-required
  [results]
  (filterv #(and (:check/required? %) (not (:check/ok? %))) results))

(defn- ^{:stratum 0} status-mark
  [{:check/keys [ok? required?]}]
  (cond
    ok?       (display/style "✓" :foreground :green)
    required? (display/style "✗" :foreground :red)
    :else     (display/style "!" :foreground :yellow)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-lines
  "Output lines for one result: mark, label, required/optional, detail;
   then its extra detail lines, indented."
  [{:check/keys [label required? detail details] :as result}]
  (let [requirement (messages/t (if required? :doctor/required :doctor/optional))
        line        (format "  %s %-22s %-9s %s" (status-mark result) label requirement detail)]
    (into [line] (map #(str "      " %)) details)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} doctor-cmd
  "Print the doctor report. Exits 1 when a required check failed."
  [opts]
  (let [results (run-checks opts)
        failed  (failed-required results)]
    (println)
    (println (display/style (app-config/system-check-title) :foreground :cyan :bold true))
    (println)
    (doseq [line (mapcat check-lines results)]
      (println line))
    (println)
    (if (seq failed)
      (do (display/print-error (messages/t :doctor/summary-failed {:count (count failed)}))
          (shared/exit! 1))
      (println (display/style (messages/t :doctor/summary-ok) :foreground :green)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (doctor-cmd {})
  (doctor-cmd {:backend :claude :execution-mode :governed})

  :end)
