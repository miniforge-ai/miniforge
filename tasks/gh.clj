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

(ns gh
  "GitHub PR inspection via the gh CLI.

   Commands:
     bb gh:pr --pr <n> [--repo owner/repo]   Summarize a PR with its reviews
                                              and inline comments
     bb gh:pr --pr <n> --reviews             Show full review bodies
     bb gh:pr --pr <n> --comments            Show all inline comments

   The repo defaults to the origin remote of the current working directory.
   Pass --repo owner/repo to inspect a PR in a different repository."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ── helpers ──────────────────────────────────────────────────────────────────

(def ^:private ellipsis
  "...")

(def ^:private output-rule-width
  80)

(def ^:private body-wrap-width
  76)

(def ^:private body-preview-width
  72)

(def ^:private reviewer-column-width
  36)

(def ^:private usage-message
  "Usage: bb gh:pr --pr <n> [--repo owner/repo] [--reviews] [--comments]")

(defn- pad [s n]
  (let [s (str s)] (str s (str/join (repeat (max 0 (- n (count s))) " ")))))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 (- n (count ellipsis))) ellipsis) s))

(defn- wrap [s width indent]
  (let [words  (str/split (str/replace (str/trim s) #"\s+" " ") #" ")
        prefix (str/join (repeat indent " "))]
    (loop [lines [] current "" words words]
      (if (empty? words)
        (str/join "\n" (if (seq current) (conj lines current) lines))
        (let [word (first words)
              candidate (if (seq current) (str current " " word) (str prefix word))]
          (if (> (count candidate) (+ width indent))
            (if (seq current)
              (recur (conj lines current) (str prefix word) (rest words))
              (recur lines (str prefix word) (rest words)))
            (recur lines candidate (rest words))))))))

;; ── arg parsing ──────────────────────────────────────────────────────────────

(defn- parse-args [args]
  (loop [xs args acc {}]
    (if-let [[x & _] (seq xs)]
      (case x
        "--pr"       (if (second xs)
                       (recur (nnext xs) (assoc acc :pr (second xs)))
                       (do (println "✗ --pr requires a value") (System/exit 1)))
        "--repo"     (if (second xs)
                       (recur (nnext xs) (assoc acc :repo (second xs)))
                       (do (println "✗ --repo requires a value") (System/exit 1)))
        "--reviews"  (recur (next xs) (assoc acc :reviews true))
        "--comments" (recur (next xs) (assoc acc :comments true))
        ("-h" "--help") (do (println usage-message)
                            (System/exit 0))
        (do (println (str "✗ Unknown arg: " x)) (System/exit 1)))
      acc)))

;; ── repo detection ────────────────────────────────────────────────────────────

(defn- detect-repo []
  (try
    (let [url (str/trim (:out (p/sh "git" "remote" "get-url" "origin")))]
      (second (re-find #"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?/?$" url)))
    (catch Exception _ nil)))

;; ── gh API calls ─────────────────────────────────────────────────────────────

(defn- gh-api [path]
  (let [{:keys [out err exit]} (p/sh {:continue true :out :string :err :string}
                                     "gh" "api" path)]
    (when-not (zero? exit)
      (println (str "✗ gh api " path " failed"))
      (when-not (str/blank? err)
        (println (str/trim err)))
      (System/exit 1))
    (json/parse-string out true)))

(defn- fetch-pr [repo pr]
  (gh-api (str "repos/" repo "/pulls/" pr)))

(defn- fetch-reviews [repo pr]
  (gh-api (str "repos/" repo "/pulls/" pr "/reviews")))

(defn- fetch-inline-comments [repo pr]
  (gh-api (str "repos/" repo "/pulls/" pr "/comments")))

;; ── display ───────────────────────────────────────────────────────────────────

(defn- state-label [s]
  (case s "APPROVED" "✓ APPROVED" "CHANGES_REQUESTED" "✗ CHANGES_REQUESTED"
    "COMMENTED" "· COMMENTED" "DISMISSED" "– DISMISSED" (str s)))

(defn- print-pr-header [pr repo]
  (println (str "==> PR #" (:number pr) ": " (:title pr)))
  (println (str "    state:  " (:state pr)
                "  by " (get-in pr [:user :login])))
  (println (str "    repo:   " repo))
  (println (str "    url:    " (:html_url pr)))
  (println (str "    diff:   +" (:additions pr) " / -" (:deletions pr))))

(defn- print-reviews [reviews show-full?]
  (when (seq reviews)
    (println)
    (println (str "Reviews (" (count reviews) "):"))
    (println (str "  " (str/join (repeat output-rule-width "─"))))
    (doseq [{:keys [user state body submitted_at]} reviews]
      (println (str "  " (pad (get user :login "?") reviewer-column-width)
                    "  " (state-label state)
                    "  " (or submitted_at "")))
      (when (and show-full? (seq body))
        (println)
        (println (wrap body body-wrap-width 4))
        (println))
      (when (and (not show-full?) (seq body))
        (println (str "    " (truncate (first (str/split-lines body)) body-preview-width)))))))

(defn- print-inline-comments [comments show-full?]
  (when (seq comments)
    (println)
    (println (str "Inline comments (" (count comments) "):"))
    (println (str "  " (str/join (repeat output-rule-width "─"))))
    (doseq [{:keys [user path line original_line body]} comments]
      (let [loc (or line original_line)]
        (println (str "  " (get user :login "?")
                      (when loc (str " @ " path ":" loc))
                      (when-not loc (str " @ " path))))
        (if show-full?
          (do (println) (println (wrap body body-wrap-width 4)) (println))
          (println (str "    " (truncate (first (str/split-lines body)) body-preview-width))))))))

;; ── entry point ──────────────────────────────────────────────────────────────

(defn pr!
  [args]
  (let [{:keys [pr repo reviews comments]} (parse-args args)
        _ (when-not pr
            (println usage-message)
            (System/exit 1))
        resolved-repo (or repo (detect-repo))
        _ (when-not resolved-repo
            (println "✗ Could not detect GitHub repo. Pass --repo owner/repo.")
            (System/exit 1))
        pr-data      (fetch-pr resolved-repo pr)
        review-data  (fetch-reviews resolved-repo pr)
        comment-data (fetch-inline-comments resolved-repo pr)]
    (print-pr-header pr-data resolved-repo)
    (print-reviews review-data (boolean reviews))
    (print-inline-comments comment-data (boolean comments))))
