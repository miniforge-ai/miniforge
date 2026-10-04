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
(ns ai.miniforge.bb-proc.core
  "Subprocess helpers. Thin wrappers around `babashka.process` so tasks
   read the same way across the umbrella and so tests can inspect
   exit/capture output uniformly.

   Layer 0: argument normalization and command-resolution planning (pure).
   Layer 1: process invocations on top of Layer 0."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Argument normalization and command planning (pure)
(def ^{:stratum 0} ^:private windows-clojure-command
  "Fallback executable names for Clojure tooling on Windows runners."
  ["clojure.exe" "clj.exe" "deps.exe"])

(defn- ^{:stratum 0} split-opts
  "Split `args` into `[opts cmd]` where `opts` is the leading map (or an
   empty map if absent) and `cmd` is the remaining command tokens."
  [args]
  (if (map? (first args))
    [(first args) (rest args)]
    [{} args]))

(defn ^{:stratum 0} first-resolved-command
  "Return the first executable path found by `lookup-fn`, else the first
   candidate name unchanged."
  [candidates lookup-fn]
  (or (some (fn [candidate]
              (some-> (lookup-fn candidate) str))
            candidates)
      (first candidates)))

(def ^{:stratum 0} ^:private max-error-tail
  "Characters of stderr appended to a `run!` failure message. The full text
   always stays in the ex-data under :err; this bound only stops a compiler or
   similar flooding a log line with its entire output."
  2000)

(defn ^{:stratum 0} installed?
  "True if `cmd` resolves on PATH. Uses `babashka.fs/which`, which is
   portable: invokes `which` on Unix shells and `where` on Windows."
  [cmd]
  (some? (fs/which cmd)))

(defn ^{:stratum 0} destroy!
  "Destroy a background process and wait briefly for it to exit."
  [proc]
  (when proc
    (p/destroy proc)
    (try (deref proc 5000 nil) (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} command-candidates
  "Return candidate executable names for `cmd` on `os`."
  [cmd os]
  (let [candidates (if (and (= os :windows) (= cmd "clojure"))
                     (into [cmd] windows-clojure-command)
                     [cmd])]
    (vec (distinct candidates))))

(defn- ^{:stratum 1} error-tail
  "Trimmed stderr for a failure message, bounded to its final
   `max-error-tail` characters. Keeps the tail rather than the head because
   that is where a failing command states the reason. Returns nil when
   nothing usable was captured."
  [err]
  (when-let [text (some-> err str/trim not-empty)]
    (if (<= (count text) max-error-tail)
      text
      (str "... (stderr truncated, full text in ex-data :err)\n"
           (subs text (- (count text) max-error-tail))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-command
  "Resolve `cmd` to an executable path when possible."
  [cmd]
  (let [windows-os? (re-find #"(?i)windows" (System/getProperty "os.name" ""))
        os          (if windows-os? :windows :unix)
        candidates  (command-candidates cmd os)]
    (first-resolved-command candidates fs/which)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} clojure-command
  "Resolve the best Clojure executable for the current process."
  []
  (resolve-command "clojure"))

(defn- ^{:stratum 3} resolved-cmd
  "Resolve the executable token in `cmd`, preserving the remaining args."
  [cmd]
  (if (seq cmd)
    (into [(resolve-command (first cmd))] (rest cmd))
    []))

;------------------------------------------------------------------------------ Layer 4

;; Process invocations
(defn ^{:stratum 4} run!
  "Boundary wrapper around the canonical result-returning `sh`.

   Run a command through babashka.process/sh. Throws ex-info on non-zero exit
   for legacy bang-callers; prefer `sh` in non-boundary code when callers can
   branch on the process result map. Accepts an optional opts map as the first
   arg, including stdio overrides such as `:out` / `:err`.

   On non-zero exit the ex-data carries :out and :err and the message appends
   stderr, so the failing command's own diagnostics travel with the error
   instead of being dropped along with the result map. Both keys are nil when
   the caller routed that stream somewhere other than a string capture, so
   `:err :inherit` still throws -- the output just went to the console."
  [& args]
  (let [[opts cmd] (split-opts args)
        result     (apply p/sh (merge {:continue true} opts) (resolved-cmd cmd))]
    (when-not (zero? (:exit result))
      (let [out  (when (string? (:out result)) (:out result))
            err  (when (string? (:err result)) (:err result))
            tail (error-tail err)]
        (throw (ex-info (cond-> (str "Command failed: " (pr-str cmd))
                          tail (str "\n" tail))
                        {:exit (:exit result) :cmd cmd :out out :err err}))))
    result))

(defn ^{:stratum 4} run-bg!
  "Start a command in the background. Returns the process handle.
   Caller is responsible for destroying it via `destroy!`."
  [& args]
  (let [[opts cmd] (split-opts args)]
    (apply p/process (merge {:out :inherit :err :inherit} opts) (resolved-cmd cmd))))

(defn ^{:stratum 4} sh
  "Run a command, capture stdout/stderr, return the result map.
   Never throws — caller inspects `:exit`."
  [& args]
  (let [[opts cmd] (split-opts args)]
    (apply p/sh opts (resolved-cmd cmd))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (sh "echo" "hi")
  (installed? "git")
  (let [p (run-bg! "sleep" "60")]
    (destroy! p))

  :leave-this-here)
