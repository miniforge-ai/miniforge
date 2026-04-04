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

(defn staged-files []
  (-> (p/sh "git" "diff" "--cached" "--name-only" "--diff-filter=ACM")
      :out
      str/split-lines
      (->> (remove str/blank?))))

(defn staged-by-ext [ext]
  (->> (staged-files)
       (filter (fn [f] (str/ends-with? f ext)))))

(defn clj-staged []
  (let [clj-files (->> (concat (staged-by-ext ".clj")
                               (staged-by-ext ".cljs")
                               (staged-by-ext ".cljc")
                               (staged-by-ext ".edn"))
                       ;; Exclude .clj-kondo imports (library configs)
                       (remove (fn [f] (str/starts-with? f ".clj-kondo/"))))]
    (if (seq clj-files)
      (let [_ (println "🔍 Linting" (count clj-files) "Clojure file(s)...")
            _ (println "Files:" (str/join ", " clj-files))
            {:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                         "clj-kondo" "--lint" clj-files)]
        (when-not (str/blank? out) (println out))
        (when-not (str/blank? err) (binding [*out* *err*] (println err)))
        ;; Exit 0 = success, 2 = warnings only, 3 = errors
        ;; Allow warnings but fail on errors
        (when (= exit 3)
          (println "❌ Linting failed with errors (exit code 3)")
          (System/exit 3)))
      (println "✓ No Clojure files to lint"))))

(defn clj-all []
  (println "🔍 Linting all Clojure files...")
  (println "Directories: bases components development/src")
  (let [{:keys [exit out err]} (p/sh {:out :string :err :string}
                                     "clj-kondo" "--lint" "bases" "components" "development/src")]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Linting failed with exit code:" exit)
      (System/exit exit))))
