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
   [ai.miniforge.connector-linter.interface :as linter]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.repo-analyzer :as analyzer]
   [ai.miniforge.compliance-scanner.interface :as scanner]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.semantic-analyzer.interface :as semantic]))

;------------------------------------------------------------------------------ Layer 0
;; Pack resolution

(def ^:private default-standards-path ".standards")
(def ^:private repo-config-path ".miniforge/config.edn")

(defn- resolve-pack
  "Resolve a pack by name or path. Returns the loaded pack map or nil."
  [pack-ref]
  (cond
    (fs/exists? pack-ref)
    (edn/read-string (slurp (str pack-ref)))

    :else
    (let [resource-path (str "policy_pack/packs/" pack-ref ".pack.edn")]
      (when-let [url (io/resource resource-path)]
        (edn/read-string (slurp url))))))

(defn- load-repo-config
  "Load .miniforge/config.edn from the repo root. Returns nil if absent."
  [repo-path]
  (let [path (fs/path repo-path repo-config-path)]
    (when (fs/exists? path)
      (edn/read-string (slurp (str path))))))

(defn- resolve-packs-from-config
  "Load all packs declared in :repo/packs. Returns merged pack or nil."
  [repo-config]
  (let [pack-names (get repo-config :repo/packs [])]
    (when (seq pack-names)
      (let [packs (keep resolve-pack pack-names)
            rules (vec (mapcat :pack/rules packs))]
        (when (seq rules)
          {:pack/rules rules})))))

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
  "Build scan options from CLI opts and repo config.
   Priority: --pack flag > repo config :repo/packs > no pack."
  [repo-path opts]
  (let [explicit-pack (when-let [p (get opts :pack)] (resolve-pack p))
        repo-config   (when-not explicit-pack (load-repo-config repo-path))
        config-pack   (when repo-config (resolve-packs-from-config repo-config))
        pack          (or explicit-pack config-pack)
        rules         (resolve-rules-selector (get opts :rules))
        since         (get opts :since)]
    (cond-> {:rules rules}
      pack  (assoc :pack pack)
      since (assoc :since since))))

(defn- print-scan-summary
  "Print scan result summary. Returns the scan result."
  [scan-result]
  (display/print-info
   (messages/t :scan/policy-summary
               {:count       (count (:violations scan-result))
                :files       (:files-scanned scan-result)
                :duration-ms (:scan-duration-ms scan-result)}))
  scan-result)

(defn- classify-and-report
  "Classify violations and print counts. Returns classified violations."
  [violations]
  (let [classified (scanner/classify violations)
        auto-count (count (filter :auto-fixable? classified))
        review-count (count (remove :auto-fixable? classified))]
    (display/print-info
     (messages/t :scan/classification
                 {:auto-count auto-count :review-count review-count}))
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
    (display/print-info (messages/t :scan/exec-banner))
    (let [exec-result (scanner/execute! plan-result repo-path)]
      (display/print-info
       (messages/t :scan/exec-summary
                   {:fixed (get exec-result :violations-fixed 0)
                    :files (get exec-result :files-changed 0)})))))

(defn- run-linters
  "Run language-specific linters for detected technologies.
   Returns vector of linter violations."
  [repo-path repo-config]
  (let [detected (get repo-config :repo/technologies #{})
        fps      @analyzer/fingerprints]
    (when (seq detected)
      (let [result (linter/run-all repo-path fps detected)]
        (doseq [{:keys [tech available? violations duration-ms]} (:linter-results result)]
          (cond
            (not available?)
            (display/print-info (messages/t :scan/linter-skipped {:tech (name tech)}))

            (seq violations)
            (display/print-info (messages/t :scan/linter-findings
                                            {:tech        (name tech)
                                             :count       (count violations)
                                             :duration-ms duration-ms}))

            :else
            (display/print-info (messages/t :scan/linter-clean
                                            {:tech (name tech) :duration-ms duration-ms}))))
        (:violations result)))))

(defn- run-linter-fixes!
  "Run linter --fix for detected technologies."
  [repo-path repo-config]
  (let [detected (get repo-config :repo/technologies #{})
        fps      @analyzer/fingerprints
        result   (linter/run-fixes repo-path fps detected)]
    (doseq [{:keys [tech exit]} (:fixed result)]
      (display/print-info
       (messages/t :scan/linter-fix-result
                   {:tech   (name tech)
                    :result (messages/t (if (zero? exit)
                                          :scan/linter-fix-applied
                                          :scan/linter-fix-failed))})))))

(defn- print-rule-result
  "Print a single rule's analysis result."
  [result]
  (let [rule-name (name (get result :rule/id :unknown))
        status    (get result :status :completed)]
    (case status
      :timeout  (display/print-info (messages/t :scan/rule-timeout {:rule rule-name}))
      :error    (display/print-info (messages/t :scan/rule-error
                                                {:rule rule-name
                                                 :message (get result :error)}))
      (display/print-info
       (messages/t :scan/rule-findings
                   {:rule        rule-name
                    :count       (count (:violations result))
                    :files       (:files-analyzed result)
                    :duration-ms (:duration-ms result)})))))

(defn- rule-has-matching-files?
  "True when a rule's file globs match at least one file in the repo."
  [repo-path rule]
  (seq (semantic/select-files-for-rule repo-path rule)))

(defn- run-semantic-analysis
  "Run LLM-based semantic analysis on behavioral rules in parallel.
   Only runs rules that have matching files in the repo."
  [repo-path standards-path]
  (try
    (let [compile-result (let [r (policy-pack/compile-standards-pack standards-path)]
                           (if (:success? r)
                             r
                             (when-let [url (io/resource "packs/miniforge-standards.pack.edn")]
                               {:success? true :pack (edn/read-string (slurp url))})))]
      (when (:success? compile-result)
        (let [all-rules   (semantic/behavioral-rules (get-in compile-result [:pack :pack/rules]))
              ;; Only run rules that have matching files — skip wrong-language rules
              rules       (filterv #(rule-has-matching-files? repo-path %) all-rules)
              skipped     (- (count all-rules) (count rules))]
          (when (pos? skipped)
            (display/print-info (messages/t :scan/semantic-skipped {:count skipped})))
          (when (seq rules)
            (let [client  (llm/create-client)
                  _       (display/print-info
                           (messages/t :scan/semantic-analyzing {:count (count rules)}))
                  results (semantic/analyze-rules-parallel
                           client llm/complete repo-path rules
                           {:timeout-ms 120000 :max-parallel 4})]
              (doseq [r results] (print-rule-result r))
              (vec (mapcat :violations results)))))))
    (catch Exception e
      (display/print-info (messages/t :scan/semantic-unavailable
                                      {:message (.getMessage e)}))
      [])))

(defn- run-scan
  "Execute the scan→linters→semantic→classify→plan→execute pipeline."
  [repo-path opts]
  (let [standards   (get opts :standards default-standards-path)
        scan-opts   (build-scan-opts repo-path opts)
        report?     (get opts :report false)
        execute?    (get opts :execute false)
        no-lint?    (get opts :no-lint false)
        semantic?   (get opts :semantic false)
        repo-config (load-repo-config repo-path)]

    ;; Phase 1: Policy pack scan
    (display/print-info (messages/t :scan/banner {:path repo-path}))
    (let [scan-result     (-> (scanner/scan repo-path standards scan-opts)
                              print-scan-summary)
          policy-viols    (:violations scan-result)

          ;; Phase 1b: Linter scan
          linter-viols    (when-not no-lint?
                            (when repo-config
                              (display/print-info (messages/t :scan/running-linters))
                              (run-linters repo-path repo-config)))

          ;; Phase 1c: Semantic analysis (LLM-as-judge)
          semantic-viols  (when semantic?
                            (display/print-info (messages/t :scan/semantic-banner))
                            (run-semantic-analysis repo-path standards))

          ;; Merge violations
          all-violations  (vec (concat policy-viols
                                       (or linter-viols [])
                                       (or semantic-viols [])))
          breakdown       (if (or (seq linter-viols) (seq semantic-viols))
                            (messages/t :scan/total-breakdown
                                        {:policy   (count policy-viols)
                                         :linter   (if (seq linter-viols)
                                                     (messages/t :scan/total-linter
                                                                 {:count (count linter-viols)})
                                                     "")
                                         :semantic (if (seq semantic-viols)
                                                     (messages/t :scan/total-semantic
                                                                 {:count (count semantic-viols)})
                                                     "")})
                            "")]

      (display/print-info
       (messages/t :scan/total-line
                   {:count (count all-violations) :breakdown breakdown}))

      (when (seq all-violations)
        (let [classified  (classify-and-report all-violations)
              plan-result (plan-and-print classified repo-path report?)]

          ;; Execute: run both linter fixes and policy auto-fixes
          (when execute?
            (when (and repo-config (not no-lint?))
              (display/print-info (messages/t :scan/linter-fix-banner))
              (run-linter-fixes! repo-path repo-config))
            (execute-if-requested plan-result repo-path true)))))))

;------------------------------------------------------------------------------ Layer 2
;; Command entry point

(defn scan-cmd
  "CLI entry point for the scan command."
  [opts]
  (let [repo-path (get opts :repo (str (fs/cwd)))]
    (cond
      (not (fs/exists? repo-path))
      (display/print-error (messages/t :scan/repo-not-found {:path repo-path}))

      :else
      (try
        (run-scan repo-path opts)
        (catch Exception e
          (display/print-error (messages/t :scan/scan-failed
                                           {:message (ex-message e)})))))))
