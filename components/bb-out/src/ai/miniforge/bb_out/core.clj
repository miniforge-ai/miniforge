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

(ns ai.miniforge.bb-out.core
  "Status output. Matches the `==>`-style section headers existing
   shell scripts use, so log output stays recognizable as we migrate
   tasks to Clojure.

   Layer 0: pure formatters — build the string without printing.
   Layer 1: side-effecting printers on top of Layer 0.")

;------------------------------------------------------------------------------ Layer 0
;; Pure formatters

(defn format-section
  "Return the string form of a section header."
  [msg]
  (str "==> " msg))

(defn format-step
  "Return the string form of an indented progress line."
  [msg]
  (str "    " msg))

(defn format-ok
  "Return the string form of a success line."
  [msg]
  (str "    \u2713 " msg))

(defn format-warn
  "Return the string form of a warning line."
  [msg]
  (str "    \u26A0 " msg))

(defn format-fail
  "Return the string form of a failure line."
  [msg]
  (str "    \u2717 " msg))

;------------------------------------------------------------------------------ Layer 1
;; Side-effecting printers

(defn section
  "Print a section header to stdout."
  [msg]
  (println (format-section msg)))

(defn step
  "Print an indented progress line to stdout."
  [msg]
  (println (format-step msg)))

(defn ok
  "Print a success line to stdout."
  [msg]
  (println (format-ok msg)))

(defn warn
  "Print a warning line to stderr."
  [msg]
  (binding [*out* *err*]
    (println (format-warn msg))))

(defn fail
  "Print a failure line to stderr."
  [msg]
  (binding [*out* *err*]
    (println (format-fail msg))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (format-section "hello")
  (section "demo")
  (warn "stderr")

  :leave-this-here)
