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
(ns ai.miniforge.gate.edited-forms
  "The lines of a file a change edits, widened to whole top-level forms in
   Clojure source and EDN.

   A change edits a top-level form when the form holds a line the change
   added, or a deletion point (where the change removed lines) between two
   of its lines or directly before it: removing a reader prefix on its own
   line, such as `^:private` or `#_`, changes the form that follows. An
   added line outside every form is edited on its own. Other files are
   edited exactly on their added lines.

   Forms are located with edamame, which reads past aliases, reader
   conditionals and tagged literals it cannot resolve, so one unknown
   `::alias/kw` does not end the read. Content it cannot read is an
   anomaly: nothing in it can be placed inside or outside an edit."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.gate.messages :as msg]
            [edamame.core :as edamame]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private form-extensions
  "File extensions whose content is read as top-level forms."
  #{"clj" "cljc" "cljs" "bb" "edn"})

(defn- ^{:stratum 0} alias-namespace
  "The namespace an auto-resolved keyword is read into: its alias as
   written, since no alias map is known (`::k`, with no alias, reads into
   `user`). Only the keyword's location matters here, not its name."
  [alias]
  (if (= :current alias) 'user alias))

(defn- ^{:stratum 0} span-lines
  "Every line number from START to END, inclusive."
  [[start end]]
  (range start (inc end)))

(defn- ^{:stratum 0} edited-span?
  "True when the form spanning START..END holds an ADDED line, or a
   deletion point P (lines removed between P and P+1) inside it or
   directly before it (P = START - 1)."
  [added deletion-points [start end]]
  (boolean (or (some #(<= start % end) added)
               (some #(and (<= (dec start) %) (< % end)) deletion-points))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} form-file?
  "True when PATH's content is read as top-level forms."
  [path]
  (contains? form-extensions (second (re-find #"\.([^./]+)$" (str path)))))

(defn ^{:stratum 1} form-spans
  "[first-line last-line] of each top-level form in CONTENT, in order, or
   an :invalid-input anomaly when edamame cannot read CONTENT. Every syntax
   is enabled, aliases and tags stay unresolved, reader conditionals are
   kept whole, and each form is read as its location."
  [content]
  (try+
    (mapv (juxt :row :end-row)
          (edamame/parse-string-all content {:all          true
                                             :read-cond    :preserve
                                             :auto-resolve alias-namespace
                                             :readers      (constantly identity)
                                             :postprocess  :loc}))
    (catch [:type :edamame/error] _
      (anomaly/exception-anomaly :invalid-input (msg/t :edited-forms/unreadable)
                                 (:throwable &throw-context)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} edited-lines
  "The set of CONTENT's line numbers a change edits, given the set of lines
   it ADDED and its DELETION-POINTS (`git-change/deletion-points`). In a
   form file every top-level form holding an added line or a deletion
   point is edited whole; an anomaly when that content cannot be read."
  [path content added deletion-points]
  (if-not (form-file? path)
    (set added)
    (let [spans (form-spans content)]
      (if (anomaly/anomaly? spans)
        spans
        (into (set added)
              (comp (filter #(edited-span? added deletion-points %))
                    (mapcat span-lines))
              spans)))))
