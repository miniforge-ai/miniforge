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
(ns bootstrap
  "Decisions `bb bootstrap` makes for a fresh clone: whether to install the
   repo's git hooks, and whether the `mf` it installed is the one on PATH.

   .githooks/pre-commit rejects every commit whose author email is not
   @miniforge.ai. miniforge's release phase commits in a worktree of the
   clone, which shares its hooks, so installing them for anyone else makes
   the first `mf run` fail at release."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} org-email-suffix
  "Commit email domain .githooks/pre-commit accepts."
  "@miniforge.ai")

(defn ^{:stratum 0} mf-path-problem
  "nil when `found` (the `mf` on PATH, or nil) is `installed`; otherwise the
   line explaining why `mf` will not run the build bootstrap installed."
  [installed found]
  (cond
    (nil? found)
    (str "⚠️  mf is installed at " installed ", but its directory is not on PATH.\n"
         "   Add it: export PATH=\"$HOME/.local/bin:$PATH\"")

    (not= (str found) (str installed))
    (str "⚠️  The mf on PATH is " found ", not " installed ".\n"
         "   Put $HOME/.local/bin earlier on PATH to use this checkout.")

    :else nil))

(defn ^{:stratum 0} git-email
  "The clone's git user.email, or \"\" when unset."
  []
  (str/trim (get (p/sh "git" "config" "user.email") :out "")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} hooks-plan
  "What bootstrap does about git hooks for a clone whose git user.email is
   `email`: {:install-hooks? bool :lines [line ...]}."
  [email]
  (let [email (str/trim (str email))]
    (cond
      (str/blank? email)
      {:install-hooks? false
       :lines ["⚠️  git user.email is not set; commits will fail."
               "   Set it with: git config user.email you@example.com"]}

      (str/ends-with? email org-email-suffix)
      {:install-hooks? true
       :lines [(str "✅ Git email: " email)]}

      :else
      {:install-hooks? false
       :lines [(str "✅ Git email: " email)
               (str "ℹ️  .githooks/pre-commit accepts only " org-email-suffix
                    " commit emails, so bootstrap does not install it for this clone."
                    " CI runs the same checks on pull requests.")]})))

(defn ^{:stratum 1} check-mf-install!
  "After install:local: exit 1 when `installed` is missing; otherwise print
   why `mf` will not resolve to it, when that is the case."
  [installed]
  (if-not (fs/exists? installed)
    (do (println "❌ mf was not installed at" installed)
        (System/exit 1))
    (when-let [problem (mf-path-problem (str (fs/canonicalize installed))
                                        (some-> (fs/which "mf") fs/canonicalize str))]
      (println problem))))
