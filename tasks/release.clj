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

(ns release
  "Release and stability tagging tasks.

   Two distinct concerns:
   - stable!  — Run full test suite, tag as stable if green. For poly change detection.
   - cut!     — Tag a DateVer release + stable tag, push to trigger CI + Homebrew."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- sh [& args]
  (let [{:keys [exit out]} (apply p/sh {:out :string :err :string} args)]
    (when (zero? exit) (str/trim out))))

(defn- today-str []
  (let [now (java.time.LocalDate/now)]
    (format "%d%02d%02d" (.getYear now) (.getMonthValue now) (.getDayOfMonth now))))

(defn- datever []
  (let [now   (java.time.LocalDate/now)
        year  (.getYear now)
        month (.getMonthValue now)
        day   (.getDayOfMonth now)
        ;; Count existing tags for today to determine N
        prefix (format "v%d.%02d.%02d." year month day)
        existing (or (sh "git" "tag" "-l" (str prefix "*")) "")
        n (inc (count (str/split-lines (str/trim existing))))]
    (format "%d.%02d.%02d.%d" year month day n)))

(defn- on-main? []
  (= "main" (sh "git" "branch" "--show-current")))

(defn- clean-working-tree? []
  (str/blank? (sh "git" "status" "--porcelain")))

;------------------------------------------------------------------------------ Layer 1
;; Stable tag — run tests, tag if green

(defn stable!
  "Run the full test suite (brick + project + integration). If green,
   push a stable-YYYYMMDD tag so poly only tests changed bricks."
  []
  (when-not (on-main?)
    (println "Must be on main to tag stable.")
    (System/exit 1))
  (println "Running full test suite...")
  (let [{:keys [exit]} (p/sh {:inherit true} "bb" "test:all")]
    (if-not (zero? exit)
      (do (println "Tests failed — not tagging.")
          (System/exit 1))
      (let [tag (str "stable-" (today-str))]
        (println (str "All tests green. Tagging " tag "..."))
        (p/shell "git" "tag" "-a" tag "-m" (str "Stable: " tag " — full suite green"))
        (p/shell "git" "push" "origin" tag)
        (println (str "Pushed " tag))))))

;------------------------------------------------------------------------------ Layer 2
;; Release — version tag + stable tag, push to trigger CI

(defn cut!
  "Tag a DateVer release + stable tag, push both to trigger the release
   workflow (CI builds binaries, creates GitHub release, updates Homebrew)."
  []
  (when-not (on-main?)
    (println "Must be on main to cut a release.")
    (System/exit 1))
  (when-not (clean-working-tree?)
    (println "Working tree is dirty. Commit or stash first.")
    (System/exit 1))
  (let [version    (datever)
        vtag       (str "v" version)
        stable-tag (str "stable-" (today-str))]
    (println (str "Cutting release " vtag "..."))
    ;; Version tag — triggers release.yml
    (p/shell "git" "tag" "-a" vtag "-m" (str "Release " version))
    ;; Stable tag — keeps poly change detection current
    (let [existing-stable (sh "git" "tag" "-l" stable-tag)]
      (when (seq existing-stable)
        (println (str "Updating existing " stable-tag " tag..."))
        (p/shell "git" "tag" "-d" stable-tag)
        (p/shell "git" "push" "origin" (str ":refs/tags/" stable-tag))))
    (p/shell "git" "tag" "-a" stable-tag "-m" (str "Stable: " vtag " release"))
    ;; Push both
    (p/shell "git" "push" "origin" vtag stable-tag)
    (println)
    (println (str "Release " vtag " tagged and pushed."))
    (println "CI will build binaries, create GitHub release, and update Homebrew.")
    (println (str "Track: gh run list --workflow release.yml --limit 1"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (datever)
  (today-str)
  (on-main?)
  (clean-working-tree?)
  :leave-this-here)
