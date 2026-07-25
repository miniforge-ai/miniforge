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
   [clojure.string :as str]
   [stratum]))

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

(defn ^{:stratum 2} stratum-staged
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
   work-in-progress the developer deliberately left unstaged. Mechanics
   live in the `stratum` namespace (rule 210: this dispatcher plus that
   namespace's own chain would be 4 real layers in one file)."
  []
  (let [files (->> (concat (staged-by-ext ".clj")
                           (staged-by-ext ".cljc"))
                   (remove (fn [f] (str/starts-with? f ".clj-kondo/"))))]
    (if (seq files)
      (let [dirty (unstaged-files)
            unsafe (filter dirty files)
            safe (remove dirty files)]
        (println "🔍 Stratum-linting" (count files) "Clojure file(s)...")
        (when (seq unsafe) (stratum/lint-only-and-fail! unsafe))
        (when (seq safe) (stratum/autofix-and-restage! safe)))
      (println "✓ No Clojure files to stratum-lint"))))
