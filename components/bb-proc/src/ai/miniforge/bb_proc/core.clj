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
            [babashka.process :as p]))

;------------------------------------------------------------------------------ Layer 0
;; Argument normalization and command planning (pure)

(def ^:private windows-clojure-command
  "Fallback executable names for Clojure tooling on Windows runners."
  ["clojure.exe" "clj.exe" "deps.exe"])

(defn- split-opts
  "Split `args` into `[opts cmd]` where `opts` is the leading map (or an
   empty map if absent) and `cmd` is the remaining command tokens."
  [args]
  (if (map? (first args))
    [(first args) (rest args)]
    [{} args]))

(defn command-candidates
  "Return candidate executable names for `cmd` on `os`."
  [cmd os]
  (let [candidates (if (and (= os :windows) (= cmd "clojure"))
                     (into [cmd] windows-clojure-command)
                     [cmd])]
    (vec (distinct candidates))))

(defn first-resolved-command
  "Return the first executable path found by `lookup-fn`, else the first
   candidate name unchanged."
  [candidates lookup-fn]
  (or (some (fn [candidate]
              (some-> (lookup-fn candidate) str))
            candidates)
      (first candidates)))

(defn resolve-command
  "Resolve `cmd` to an executable path when possible."
  [cmd]
  (let [windows-os? (re-find #"(?i)windows" (System/getProperty "os.name" ""))
        os          (if windows-os? :windows :unix)
        candidates  (command-candidates cmd os)]
    (first-resolved-command candidates fs/which)))

(defn clojure-command
  "Resolve the best Clojure executable for the current process."
  []
  (resolve-command "clojure"))

(defn- resolved-cmd
  "Resolve the executable token in `cmd`, preserving the remaining args."
  [cmd]
  (if (seq cmd)
    (into [(resolve-command (first cmd))] (rest cmd))
    []))

;------------------------------------------------------------------------------ Layer 1
;; Process invocations

(defn run!
  "Run a command inheriting stdio. Throws ex-info on non-zero exit.
   Accepts an optional opts map as the first arg."
  [& args]
  (let [[opts cmd] (split-opts args)
        result     (apply p/sh (merge {:continue true} opts) (resolved-cmd cmd))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed: " (pr-str cmd))
                      {:exit (:exit result) :cmd cmd})))
    result))

(defn run-bg!
  "Start a command in the background. Returns the process handle.
   Caller is responsible for destroying it via `destroy!`."
  [& args]
  (let [[opts cmd] (split-opts args)]
    (apply p/process (merge {:out :inherit :err :inherit} opts) (resolved-cmd cmd))))

(defn sh
  "Run a command, capture stdout/stderr, return the result map.
   Never throws — caller inspects `:exit`."
  [& args]
  (let [[opts cmd] (split-opts args)]
    (apply p/sh opts (resolved-cmd cmd))))

(defn installed?
  "True if `cmd` resolves on PATH. Uses `babashka.fs/which`, which is
   portable: invokes `which` on Unix shells and `where` on Windows."
  [cmd]
  (some? (fs/which cmd)))

(defn destroy!
  "Destroy a background process and wait briefly for it to exit."
  [proc]
  (when proc
    (p/destroy proc)
    (try (deref proc 5000 nil) (catch Exception _ nil))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (sh "echo" "hi")
  (installed? "git")
  (let [p (run-bg! "sleep" "60")]
    (destroy! p))

  :leave-this-here)
