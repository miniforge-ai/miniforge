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

(defn ^{:stratum 0} unstaged-files
  "Files with working-tree changes beyond what's staged — a partially
   staged file (e.g. after `git add -p`) shows up here too."
  []
  (-> (p/sh "git" "diff" "--name-only")
      :out
      str/split-lines
      (->> (remove str/blank?))
      set))

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

(defn ^{:stratum 0} restage!
  "Re-stage `files` after autofix; fails the commit if `git add` itself
   fails, rather than silently leaving the fixed content unstaged."
  [files]
  (doseq [f files]
    (let [{:keys [exit err]} (p/sh "git" "add" f)]
      (when-not (zero? exit)
        (println "❌ Failed to re-stage" f "after autofix:" err)
        (System/exit exit)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} staged-by-ext [ext]
  (->> (staged-files)
       (filter (fn [f] (str/ends-with? f ext)))))

(defn ^{:stratum 1} lint-only-and-fail!
  "Plain (non-fix) lint over `files`; fails the commit on any finding.
   Used for files with unstaged changes beyond what's staged, where
   autofixing would risk silently staging work-in-progress the developer
   left out on purpose."
  [files]
  (println "⚠️  Skipping autofix for partially-staged file(s) — checking only:"
           (str/join ", " files))
  (let [{:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                      "bb" "-Sdeps" stratum-lint-deps
                                      "-m" "stratum-lint.interface"
                                      files)]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Stratified-design lint failed — stage the file fully to"
               "allow autofix, or fix headings by hand. Exit code:" exit)
      (System/exit exit))))

(defn ^{:stratum 1} advisory-lint!
  "Plain (non-fix) lint pass over already-fixed `files`; prints any
   remaining findings (in practice always SL003 — over the layer budget,
   needs a namespace split — since --fix resolves everything else) as a
   non-blocking advisory. Only a genuinely unexpected exit (neither clean
   nor findings-present) fails the commit — a broken tool invocation
   should never pass silently."
  [files]
  (let [{:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                      "bb" "-Sdeps" stratum-lint-deps
                                      "-m" "stratum-lint.interface"
                                      files)]
    (when-not (str/blank? out)
      (println "⚠️  Stratified-design findings remain after autofix (often a namespace split needed for an over-budget file):")
      (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (contains? #{0 1} exit)
      (println "❌ Post-fix advisory lint pass could not run — exit code:" exit)
      (System/exit exit))))

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

(defn ^{:stratum 2} autofix-and-restage!
  "Run --fix over fully-staged `files`, re-stage on success. Exit
   contract in --fix mode: 0 covers \"already clean\" and \"successfully
   fixed\"; 1 is reserved for what --fix genuinely cannot resolve on its
   own (a parse failure, or a same-file reference cycle) and still fails
   the commit for a human to look at."
  [files]
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
        (restage! files)
        (advisory-lint! files)))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} stratum-staged
  "Autofix, the same shape `bb fmt:md` already uses (fmt/md-staged runs
   markdownlint --fix and re-stages): stratum-lint infers each def's real
   stratum from the same-file reference graph and regroups under
   regenerated headings, so a decorative/misordered heading never reaches
   a commit. Files without Layer headings are ignored by the linter, so
   unannotated legacy namespaces pass untouched — enforcement tightens
   file-by-file as headings appear.

   A file with unstaged changes beyond what's staged (e.g. after `git add
   -p`) is lint-checked instead of autofixed — --fix operates on the
   working-tree file, and re-staging it whole would silently include
   work-in-progress the developer deliberately left unstaged."
  []
  (let [files (->> (concat (staged-by-ext ".clj")
                           (staged-by-ext ".cljc"))
                   (remove (fn [f] (str/starts-with? f ".clj-kondo/"))))]
    (if (seq files)
      (let [dirty (unstaged-files)
            unsafe (filter dirty files)
            safe (remove dirty files)]
        (println "🔍 Stratum-linting" (count files) "Clojure file(s)...")
        (when (seq unsafe) (lint-only-and-fail! unsafe))
        (when (seq safe) (autofix-and-restage! safe)))
      (println "✓ No Clojure files to stratum-lint"))))
