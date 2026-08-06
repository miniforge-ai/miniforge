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
(ns ai.miniforge.cli.workflow-runner.listing
  "Catalog listings for the CLI: registered workflows and chain
   definitions. Split out of `ai.miniforge.cli.workflow-runner`
   (rule 210: the parent namespace measured 10 real layers, max 3;
   each concern moves to its own layer-coherent file)."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} format-workflow-listing [workflows]
  (if (empty? workflows)
    (println (messages/t :workflow-runner/no-workflows))
    (do
      (println (display/colorize :cyan (messages/t :workflow-runner/available-workflows)))
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (doseq [{:workflow/keys [id version description type]} workflows]
        (println (str (display/colorize :bold (str "  " (name id)))
                      " (v" version ")"
                      "  " (display/colorize :yellow (messages/t :workflow-runner/workflow-type-label {:type (or type :unknown)}))
                      (when description (str "\n    " description))))
        (println))
      (println (display/colorize :cyan (apply str (repeat 60 "─")))))))

(defn ^{:stratum 0} list-chains!
  "List all available chain definitions."
  []
  (try
    (let [chains (workflow/list-chains)]
      (if (empty? chains)
        (println (messages/t :workflow-runner/no-chains))
        (do
          (println (display/colorize :cyan (messages/t :workflow-runner/available-chains)))
          (println (display/colorize :cyan (apply str (repeat 60 "─"))))
          (doseq [{:keys [id version description steps]} chains]
            (println (str (display/colorize :bold (str "  " (name id)))
                          " (v" version ")"
                          "  " (messages/t :workflow-runner/chain-steps-label {:steps steps})))
            (when description
              (println (str "    " description)))
            (println))
          (println (display/colorize :cyan (apply str (repeat 60 "─")))))))
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-chains-failed {:error (ex-message e)})))
      (throw e))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} list-workflows-from-resources []
  (let [list-workflows workflow/list-workflows]
    (->> (list-workflows)
         (sort-by (juxt :workflow/id :workflow/version))
         format-workflow-listing)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} list-workflows! []
  (try
    (list-workflows-from-resources)
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-failed {:error (ex-message e)})))
      (throw e))))
