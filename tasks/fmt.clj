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
(ns fmt
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [fmt-wrap]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} staged-files []
  (-> (p/sh "git" "diff" "--cached" "--name-only" "--diff-filter=ACM")
      :out
      str/split-lines
      (->> (remove str/blank?))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} staged-by-ext [ext]
  (->> (staged-files)
       (filter (fn [f] (str/ends-with? f ext)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} md-staged []
  (let [md-files (->> (staged-by-ext ".md")
                      ;; Markdown under test-resources/ or fixtures/ is DATA —
                      ;; verbatim copies of a generator's output that tests
                      ;; parse. Wrapping it breaks the verbatim property and
                      ;; can split a frontmatter value across lines, corrupting
                      ;; what the fixture exists to pin. Prose formatting is
                      ;; for prose.
                      (remove #(re-find #"(?i)(^|/)(test-resources|fixtures?)/" %)))]
    (if (seq md-files)
      (do
        (println "📝 Formatting" (count md-files) "Markdown file(s)...")
        ;; Wrap long prose lines before linting
        (doseq [f md-files]
          (when (fmt-wrap/wrap-md-file! f)
            (println "  ↳ wrapped long lines in" f)))
        (let [{:keys [exit]} (apply p/sh "markdownlint" "--fix" md-files)]
          (when-not (zero? exit)
            (System/exit exit)))
        ;; Re-add the fixed files to staging
        (doseq [f md-files]
          (p/sh "git" "add" f)))
      (println "✓ No Markdown files to format"))))
