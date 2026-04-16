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
   [ai.miniforge.workflow-security-compliance.phases :as phases]
   [ai.miniforge.workflow-security-compliance.interface]))

;------------------------------------------------------------------------------ Helpers

(defn- separator [label]
  (println)
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println (str " " label))
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println))

(defn- print-violation-summary [violations]
  (doseq [v violations]
    (println (format "  [%s] %-8s %s"
                     (:violation/rule-id v)
                     (name (:violation/severity v))
                     (subs (:violation/message v) 0
                           (min 70 (count (:violation/message v))))))))

(defn- print-classified [classified]
  (let [by-cat (group-by :classification/category classified)]
    (doseq [[cat items] (sort-by (comp str key) by-cat)]
      (println (format "  %-22s %d items  (confidence: %.0f%%–%.0f%%)"
                       (name cat)
                       (count items)
                       (* 100.0 (apply min (map :classification/confidence items)))
                       (* 100.0 (apply max (map :classification/confidence items)))))
      (doseq [v items]
        (println (format "    • %s  %s  [%s]"
                         (:violation/rule-id v)
                         (get-in v [:violation/location :file] "?")
                         (name (or (:classification/doc-status v) :unknown))))))))

;------------------------------------------------------------------------------ Main

(defn -main
  "Run the 5-phase security compliance workflow against sample fixtures."
  [& _args]
  (separator "Miniforge — Security Compliance Workflow Demo (issue #552)")

  (let [output-dir (str (System/getProperty "java.io.tmpdir")
                        "/miniforge-demo-" (System/currentTimeMillis))

        ;; Build initial context
        ctx {:execution/id            (random-uuid)
             :execution/input         {:scan-paths  ["test/fixtures/sample-scan.sarif"
                                                      "test/fixtures/sample-scan.csv"]
                                        :output-dir  output-dir}
             :execution/metrics       {:duration-ms 0}
             :execution/phase-results {}}]

    ;; ─── Phase 1: Parse Scan ───
    (separator "Phase 1: Parse Scan (SARIF + CSV)")
    (let [after-parse (-> ctx
                          phases/enter-sec-parse-scan
                          (assoc-in [:phase :started-at] (System/currentTimeMillis))
                          phases/leave-sec-parse-scan)
          violations  (get-in after-parse [:execution/phase-results :sec-parse-scan
                                           :result :output :violations])]
      (println (format "  Parsed %d violations from %d files"
                       (count violations)
                       (count (get-in ctx [:execution/input :scan-paths]))))
      (print-violation-summary violations)

      ;; ─── Phase 2: Trace Source ───
      (separator "Phase 2: Trace Source (stub — LLM-assisted in production)")
      (let [after-trace (-> after-parse
                            phases/enter-sec-trace-source
                            (assoc-in [:phase :started-at] (System/currentTimeMillis))
                            phases/leave-sec-trace-source)
            traced      (get-in after-trace [:execution/phase-results :sec-trace-source
                                             :result :output :traced-violations])
            dynamic     (filter :trace/dynamic-load? traced)]
        (println (format "  Traced %d violations, %d involve dynamic loading"
                         (count traced) (count dynamic)))

        ;; ─── Phase 3: Verify Docs ───
        (separator "Phase 3: Verify Docs (mechanical + stub)")
        (let [after-verify (-> after-trace
                               phases/enter-sec-verify-docs
                               (assoc-in [:phase :started-at] (System/currentTimeMillis))
                               phases/leave-sec-verify-docs)
              verified     (get-in after-verify [:execution/phase-results :sec-verify-docs
                                                 :result :output :verified-violations])
              documented   (filter :verified/documented? verified)
              undocumented (remove :verified/documented? verified)]
          (println (format "  Verified %d violations: %d documented, %d undocumented"
                           (count verified) (count documented) (count undocumented)))

          ;; ─── Phase 4: Classify ───
          (separator "Phase 4: Classify (deterministic rules)")
          (let [after-classify (-> after-verify
                                   phases/enter-sec-classify
                                   (assoc-in [:phase :started-at] (System/currentTimeMillis))
                                   phases/leave-sec-classify)
                classified     (get-in after-classify [:execution/phase-results :sec-classify
                                                       :result :output :classified-violations])
                metrics        (get-in after-classify [:phase :metrics])]
            (println "  Classification results:")
            (println (format "    True positives:      %d" (:true-positives metrics 0)))
            (println (format "    False positives:     %d" (:false-positives metrics 0)))
            (println (format "    Needs investigation: %d" (:needs-review metrics 0)))
            (println)
            (print-classified classified)

            ;; ─── Phase 5: Generate Exclusions ───
            (separator "Phase 5: Generate Exclusions")
            (let [after-exclusions (-> after-classify
                                       phases/enter-sec-generate-exclusions
                                       (assoc-in [:phase :started-at] (System/currentTimeMillis))
                                       phases/leave-sec-generate-exclusions)
                  excl-output      (get-in after-exclusions [:execution/phase-results
                                                             :sec-generate-exclusions
                                                             :result :output])
                  excl-file        (io/file output-dir ".security-exclusions" "exclusions.edn")]
              (println (format "  Excluded (false positives):  %d" (:total-excluded excl-output 0)))
              (println (format "  Flagged (needs action):      %d" (:total-flagged excl-output 0)))
              (println)

              (when (.exists excl-file)
                (println (format "  Exclusion file written to: %s" (.getAbsolutePath excl-file)))
                (println)
                (println "  Exclusion list contents:")
                (pp/pprint (:exclusion-list excl-output)))

              ;; ─── Summary ───
              (separator "Pipeline Summary")
              (let [total-duration (get-in after-exclusions [:execution/metrics :duration-ms] 0)
                    phases-done   (get-in after-exclusions [:execution :phases-completed])]
                (println (format "  Phases completed: %d / 5" (count phases-done)))
                (println (format "  Total duration:   %d ms" total-duration))
                (println (format "  Input files:      %d" (count (get-in ctx [:execution/input :scan-paths]))))
                (println (format "  Total violations: %d" (count classified)))
                (println (format "  True positives:   %d (action required)"
                                 (count (filter #(= :true-positive (:classification/category %)) classified))))
                (println (format "  False positives:  %d (excluded)"
                                 (count (filter #(= :false-positive (:classification/category %)) classified))))
                (println (format "  Needs review:     %d (manual investigation)"
                                 (count (filter #(= :needs-investigation (:classification/category %)) classified))))
                (println)
                (println "  ✓ Demo complete. Issue #552 vertical slice functional.")))))))))
