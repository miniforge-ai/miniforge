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

(ns demo-security-compliance
  "Demo script — runs the full security-compliance workflow against fixtures.

   Usage (from the workflow-security-compliance component root):
     clj -M -m demo-security-compliance

   Or from a REPL:
     (require 'demo-security-compliance)
     (demo-security-compliance/-main)"
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [ai.miniforge.workflow-security-compliance.messages :as msg]
   [ai.miniforge.workflow-security-compliance.phases :as phases]
   [ai.miniforge.workflow-security-compliance.interface]))

;------------------------------------------------------------------------------ Helpers

(defn- separator [label]
  (let [separator-line "------------------------------------------------------------"]
    (println)
    (println separator-line)
    (println (str " " label))
    (println separator-line)
    (println)))

(defn- print-violation-summary [violations]
  (doseq [v violations]
    (let [rule-id (:violation/rule-id v)
          severity (name (:violation/severity v))
          message (:violation/message v)
          preview (subs message 0 (min 70 (count message)))]
      (println (format "  [%s] %-8s %s" rule-id severity preview)))))

(defn- print-classified [classified]
  (let [by-cat (group-by :classification/category classified)]
    (doseq [[cat items] (sort-by (comp str key) by-cat)]
      (let [category (name cat)
            item-count (count items)
            min-confidence (* 100.0 (apply min (map :classification/confidence items)))
            max-confidence (* 100.0 (apply max (map :classification/confidence items)))]
        (println (msg/t :demo/category-summary {:category category
                                                :count item-count
                                                :min min-confidence
                                                :max max-confidence})))
      (doseq [v items]
        (let [rule-id (:violation/rule-id v)
              file (get-in v [:violation/location :file] "?")
              doc-status (name (get v :classification/doc-status :unknown))]
          (println (msg/t :demo/category-item {:rule-id rule-id
                                               :file file
                                               :doc-status (if (= "unknown" doc-status)
                                                             (msg/t :demo/doc-status-unknown)
                                                             doc-status)})))))))

;------------------------------------------------------------------------------ Main

(defn -main
  "Run the 5-phase security compliance workflow against sample fixtures."
  [& _args]
  (separator (msg/t :demo/title))
  (let [output-dir (str (System/getProperty "java.io.tmpdir")
                        "/miniforge-demo-" (System/currentTimeMillis))
        ctx {:execution/id (random-uuid)
             :execution/input {:scan-paths ["test/fixtures/sample-scan.sarif"
                                            "test/fixtures/sample-scan.csv"]
                               :output-dir output-dir}
             :execution/metrics {:duration-ms 0}
             :execution/phase-results {}}
        after-parse (-> ctx
                        phases/enter-sec-parse-scan
                        (assoc-in [:phase :started-at] (System/currentTimeMillis))
                        phases/leave-sec-parse-scan)
        violations (get-in after-parse [:execution/phase-results :sec-parse-scan
                                        :result :output :violations])
        after-trace (-> after-parse
                        phases/enter-sec-trace-source
                        (assoc-in [:phase :started-at] (System/currentTimeMillis))
                        phases/leave-sec-trace-source)
        traced (get-in after-trace [:execution/phase-results :sec-trace-source
                                    :result :output :traced-violations])
        dynamic (filter :trace/dynamic-load? traced)
        after-verify (-> after-trace
                         phases/enter-sec-verify-docs
                         (assoc-in [:phase :started-at] (System/currentTimeMillis))
                         phases/leave-sec-verify-docs)
        verified (get-in after-verify [:execution/phase-results :sec-verify-docs
                                       :result :output :verified-violations])
        documented (filter :verified/documented? verified)
        undocumented (remove :verified/documented? verified)
        after-classify (-> after-verify
                           phases/enter-sec-classify
                           (assoc-in [:phase :started-at] (System/currentTimeMillis))
                           phases/leave-sec-classify)
        classified (get-in after-classify [:execution/phase-results :sec-classify
                                           :result :output :classified-violations])
        metrics (get-in after-classify [:phase :metrics])
        after-exclusions (-> after-classify
                             phases/enter-sec-generate-exclusions
                             (assoc-in [:phase :started-at] (System/currentTimeMillis))
                             phases/leave-sec-generate-exclusions)
        excl-output (get-in after-exclusions [:execution/phase-results
                                              :sec-generate-exclusions
                                              :result :output])
        excl-file (io/file output-dir ".security-exclusions" "exclusions.edn")
        total-duration (get-in after-exclusions [:execution/metrics :duration-ms] 0)
        phases-done (get-in after-exclusions [:execution :phases-completed])]
    (separator (msg/t :demo/phase-parse))
    (println (msg/t :demo/parsed-summary {:violations (count violations)
                                          :files (count (get-in ctx [:execution/input :scan-paths]))}))
    (print-violation-summary violations)

    (separator (msg/t :demo/phase-trace))
    (println (msg/t :demo/traced-summary {:violations (count traced)
                                          :dynamic (count dynamic)}))

    (separator (msg/t :demo/phase-verify))
    (println (msg/t :demo/verified-summary {:violations (count verified)
                                            :documented (count documented)
                                            :undocumented (count undocumented)}))

    (separator (msg/t :demo/phase-classify))
    (println (msg/t :demo/classification-results))
    (println (msg/t :demo/metric-true-positives {:count (:true-positives metrics 0)}))
    (println (msg/t :demo/metric-false-positives {:count (:false-positives metrics 0)}))
    (println (msg/t :demo/metric-needs-review {:count (:needs-review metrics 0)}))
    (println)
    (print-classified classified)

    (separator (msg/t :demo/phase-exclusions))
    (println (msg/t :demo/excluded-summary {:count (:total-excluded excl-output 0)}))
    (println (msg/t :demo/flagged-summary {:count (:total-flagged excl-output 0)}))
    (println)

    (when (.exists excl-file)
      (println (msg/t :demo/exclusions-path {:path (.getAbsolutePath excl-file)}))
      (println)
      (println (msg/t :demo/exclusions-heading))
      (pp/pprint (:exclusion-list excl-output)))

    (separator (msg/t :demo/phase-summary))
    (println (msg/t :demo/phases-completed {:count (count phases-done)}))
    (println (msg/t :demo/total-duration {:count total-duration}))
    (println (msg/t :demo/input-files {:count (count (get-in ctx [:execution/input :scan-paths]))}))
    (println (msg/t :demo/total-violations {:count (count classified)}))
    (println (msg/t :demo/true-positives-summary
                    {:count (count (filter #(= :true-positive (:classification/category %)) classified))}))
    (println (msg/t :demo/false-positives-summary
                    {:count (count (filter #(= :false-positive (:classification/category %)) classified))}))
    (println (msg/t :demo/needs-review-summary
                    {:count (count (filter #(= :needs-investigation (:classification/category %)) classified))}))
    (println)
    (println (msg/t :demo/complete))))
