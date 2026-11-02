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
(ns ai.miniforge.deliberation-harness.report
  "The development report: a derived Markdown summary of cell results. The
   journal is authoritative. The report always states G0 is not evaluated
   and makes no significance claim (N15 §0.5, §8.1)."
  (:require
   [ai.miniforge.deliberation-harness.codec :as codec]
   [ai.miniforge.deliberation-harness.messages :as messages]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} usd-format "%.6f")

(defn- ^{:stratum 0} printed-sort [groups]
  (sort-by (comp pr-str key) groups))

(defn- ^{:stratum 0} successes [rows]
  (reduce + (map #(get-in % [:score :success] 0) rows)))

(defn- ^{:stratum 0} mean-usd [rows]
  (format usd-format (/ (reduce + (map :normalized-usd rows)) (count rows))))

(defn- ^{:stratum 0} condition-row [[[condition task tier] rows]]
  (messages/t :report/row {:condition (name condition) :task (name task) :tier (name tier)
                           :successes (successes rows) :runs (count rows)
                           :mean-usd (mean-usd rows)
                           :unsettled (count (filter :usage-unknown? rows))}))

(defn- ^{:stratum 0} paired-delta
  "The shared-minus-blind success delta for one task/tier/replicate, or nil
   when either arm is missing."
  [[[task tier replicate] rows]]
  (let [by-condition (into {} (map (juxt :condition identity)) rows)
        shared (get-in by-condition [:workspace :score :success])
        blind (get-in by-condition [:workspace-ablated :score :success])]
    (when (and shared blind)
      (messages/t :report/delta {:task (name task) :tier (name tier)
                                 :replicate replicate :delta (- shared blind)}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} render
  "Markdown for `results` under `manifest`."
  [manifest results]
  (let [cells (printed-sort (group-by (juxt :condition :task :tier) results))
        pairs (printed-sort (group-by (juxt :task :tier :replicate) results))]
    (str/join "\n"
              (concat [(messages/t :report/heading) ""
                       (messages/t :report/manifest {:digest (codec/digest manifest)}) ""
                       (messages/t :report/disclaimer) ""
                       (messages/t :report/table-header)
                       (messages/t :report/table-rule)]
                      (map condition-row cells)
                      ["" (messages/t :report/delta-heading) ""]
                      (keep paired-delta pairs)
                      [""]))))
