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
(ns lint
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} staged-files []
  (-> (p/sh "git" "diff" "--cached" "--name-only" "--diff-filter=ACM")
      :out
      str/split-lines
      (->> (remove str/blank?))))

(defn ^{:stratum 0} clj-all []
  (println "🔍 Linting all Clojure files...")
  (println "Directories: bases components development/src")
  (let [{:keys [exit out err]} (p/sh {:out :string :err :string}
                                     "clj-kondo" "--lint" "bases" "components" "development/src")]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Linting failed with exit code:" exit)
      (System/exit exit))))

(def ^{:stratum 0} ^:private stratum-lint-deps
  "Sha-pinned git coordinate for the stratum-lint Clojure component
   (bb-native; the linter for stratified-design separator comments whose
   heading ends in `Layer N`, miniforge-standards rule 210). Resolved lazily in a
   subprocess via `bb -Sdeps` so plain `bb <task>` invocations — CI
   included — never fetch the sibling repo; only the pre-commit gate
   pays the one-time clone."
  (pr-str {:deps {'io.github.miniforge-ai/stratum-lint
                  {:git/sha "acd82a2f5c0155cb03d92ce1f4465cc064125895"
                   :deps/root "clojure"}}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} staged-by-ext [ext]
  (->> (staged-files)
       (filter (fn [f] (str/ends-with? f ext)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} clj-staged []
  (let [clj-files (->> (concat (staged-by-ext ".clj")
                               (staged-by-ext ".cljs")
                               (staged-by-ext ".cljc")
                               (staged-by-ext ".edn"))
                       ;; Exclude .clj-kondo imports (library configs)
                       (remove (fn [f] (str/starts-with? f ".clj-kondo/")))
                       ;; Exclude generated pack EDN (machine-generated, not parseable by kondo)
                       (remove (fn [f] (str/ends-with? f ".pack.edn"))))]
    (if (seq clj-files)
      (let [_ (println "🔍 Linting" (count clj-files) "Clojure file(s)...")
            _ (println "Files:" (str/join ", " clj-files))
            {:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                         "clj-kondo" "--cache" "true" "--lint" clj-files)]
        (when-not (str/blank? out) (println out))
        (when-not (str/blank? err) (binding [*out* *err*] (println err)))
        ;; Exit 0 = success, 2 = warnings only, 3 = errors
        ;; Allow warnings but fail on errors
        (when (= exit 3)
          (println "❌ Linting failed with errors (exit code 3)")
          (System/exit 3)))
      (println "✓ No Clojure files to lint"))))

(defn ^{:stratum 2} stratum-staged []
  (let [files (->> (concat (staged-by-ext ".clj")
                           (staged-by-ext ".cljc"))
                   (remove (fn [f] (str/starts-with? f ".clj-kondo/"))))]
    (if (seq files)
      (do
        (println "🔍 Stratum-linting" (count files) "Clojure file(s)...")
        ;; Autofix, the same shape as fmt:md-staged's `lint --fix`:
        ;; stratum-lint infers each def's real stratum from the same-file
        ;; reference graph and regroups under regenerated headings, so a
        ;; decorative/misordered heading never reaches a commit. Exit
        ;; contract in --fix mode: 0 covers "already clean" and
        ;; "successfully fixed"; 1 is reserved for what --fix genuinely
        ;; cannot resolve on its own (a parse failure, or a same-file
        ;; reference cycle) and still fails the commit for a human to
        ;; look at. Files without Layer headings are ignored by the
        ;; linter, so unannotated legacy namespaces pass untouched —
        ;; enforcement tightens file-by-file as headings appear.
        (let [{:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                            "bb" "-Sdeps" stratum-lint-deps
                                            "-m" "stratum-lint.interface" "--fix"
                                            files)]
          (when-not (str/blank? out) (println out))
          (when-not (str/blank? err) (binding [*out* *err*] (println err)))
          (if-not (zero? exit)
            (do
              (println "❌ Stratified-design lint could not auto-fix — exit code:" exit)
              (System/exit exit))
            (do
              (doseq [f files] (p/sh "git" "add" f))
              ;; Autofix regroups defs; it can't split a file that still
              ;; has more real strata than the budget (3) — that needs an
              ;; actual namespace split (rule 210). Surface it as advisory
              ;; output rather than blocking the commit.
              (let [{:keys [out]} (apply p/sh {:out :string :err :string}
                                        "bb" "-Sdeps" stratum-lint-deps
                                        "-m" "stratum-lint.interface"
                                        files)]
                (when-not (str/blank? out)
                  (println "⚠️  Still needs a namespace split after autofix (over the 3-layer budget):")
                  (println out)))))))
      (println "✓ No Clojure files to stratum-lint"))))
