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

(ns ai.miniforge.cli.main.commands.scan
  "Scan command — run compliance scanner against a repository.

   Usage:
     bb miniforge scan [repo-path] [--pack <pack>] [--rules <selector>]
                       [--since <ref>] [--execute] [--report]"
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.compliance-scanner.interface :as scanner]))

;------------------------------------------------------------------------------ Layer 0
;; Pack resolution

(def ^:private default-standards-path ".standards")

(defn- resolve-pack
  "Resolve a pack by name or path. Returns the loaded pack map or nil."
  [pack-ref]
  (cond
    ;; File path
    (fs/exists? pack-ref)
    (edn/read-string (slurp (str pack-ref)))

    ;; Reference pack name — load from classpath
    :else
    (let [resource-path (str "policy_pack/packs/" pack-ref ".pack.edn")]
      (when-let [url (io/resource resource-path)]
        (edn/read-string (slurp url))))))

(defn- resolve-rules-selector
  "Parse the --rules option into a selector value."
  [rules-opt]
  (cond
    (nil? rules-opt)           :all
    (= "all" rules-opt)        :all
    (= "always-apply" rules-opt) :always-apply
    :else                      (keyword rules-opt)))

;------------------------------------------------------------------------------ Layer 1
;; Pipeline steps

(defn- build-scan-opts
  "Build scan options from CLI opts."
  [opts]
  (let [pack  (when-let [p (get opts :pack)] (resolve-pack p))
        rules (resolve-rules-selector (get opts :rules))
        since (get opts :since)]
    (cond-> {:rules rules}
      pack  (assoc :pack pack)
      since (assoc :since since))))

(defn- print-scan-summary
  "Print scan result summary. Returns the scan result."
  [scan-result]
  (display/print-info
   (str "Found " (count (:violations scan-result)) " violation(s) across "
        (:files-scanned scan-result) " files ("
        (:scan-duration-ms scan-result) "ms)"))
  scan-result)

(defn- classify-and-report
  "Classify violations and print counts. Returns classified violations."
  [violations]
  (let [classified (scanner/classify violations)
        auto-count (count (filter :auto-fixable? classified))
        review-count (count (remove :auto-fixable? classified))]
    (display/print-info
     (str "  Auto-fixable: " auto-count "  Needs review: " review-count))
    classified))

(defn- plan-and-print
  "Generate plan, optionally print work spec. Returns plan result."
  [classified repo-path report?]
  (let [plan-result (scanner/plan classified repo-path)]
    (when report?
      (println)
      (println (:work-spec plan-result)))
    plan-result))

(defn- execute-if-requested
  "Apply auto-fixes if --execute flag is set."
  [plan-result repo-path execute?]
  (when (and execute? (seq (filter :auto-fixable? (mapcat :task/violations (:dag-tasks plan-result)))))
    (display/print-info "Executing auto-fixes...")
    (let [exec-result (scanner/execute! plan-result repo-path)]
      (display/print-info
       (str "Fixed " (get exec-result :violations-fixed 0)
            " violation(s) across "
            (get exec-result :files-changed 0) " file(s)")))))

(defn- run-scan
  "Execute the scan→classify→plan→execute pipeline."
  [repo-path opts]
  (let [standards  (get opts :standards default-standards-path)
        scan-opts  (build-scan-opts opts)
        report?    (get opts :report false)
        execute?   (get opts :execute false)]
    (display/print-info (str "Scanning " repo-path " ..."))
    (let [scan-result (-> (scanner/scan repo-path standards scan-opts)
                          print-scan-summary)
          violations  (:violations scan-result)]
      (when (seq violations)
        (let [classified  (classify-and-report violations)
              plan-result (plan-and-print classified repo-path report?)]
          (execute-if-requested plan-result repo-path execute?))))))

;------------------------------------------------------------------------------ Layer 2
;; Command entry point

(defn scan-cmd
  "CLI entry point for the scan command."
  [opts]
  (let [repo-path (get opts :repo (str (fs/cwd)))]
    (cond
      (not (fs/exists? repo-path))
      (display/print-error (str "Repository not found: " repo-path))

      :else
      (try
        (run-scan repo-path opts)
        (catch Exception e
          (display/print-error (str "Scan failed: " (ex-message e))))))))
