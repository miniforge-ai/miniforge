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
   added, or lines it purely removed from between two of the form's lines.
   It also edits the form directly below removed lines that start with `^`
   or `#_`: removing a reader prefix such as `^:private` changes the form
   that follows. An added line outside every form is edited on its own.
   Other files are edited exactly on their added lines.

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
  "The namespace an auto-resolved keyword is read into when no alias map is
   known: one per alias (`::k` has the alias :current) that no keyword
   literal can name, since it holds a space. So `::k` and `:user/k` stay
   distinct, as they are in Clojure."
  [alias]
  (symbol (str "auto-resolved " alias)))

(defn- ^{:stratum 0} tagged-reader
  "A reader for TAG that keeps the tag with its value, so `#inst \"x\"` and
   the string \"x\" stay distinct literals."
  [tag]
  (partial tagged-literal tag))

(defn- ^{:stratum 0} span-lines
  "Every line number from START to END, inclusive."
  [[start end]]
  (range start (inc end)))

(defn- ^{:stratum 0} reader-prefix?
  "True when removed line TEXT starts with a reader prefix (`^` metadata or
   a `#_` discard), which applies to the form that follows it."
  [text]
  (boolean (re-find #"^\s*(\^|#_)" text)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} form-file?
  "True when PATH's content is read as top-level forms."
  [path]
  (contains? form-extensions (second (re-find #"\.([^./]+)$" (str path)))))

(defn- ^{:stratum 1} edited-span?
  "True when the form spanning START..END holds an ADDED line, or one of
   REMOVALS (`git-change/removals`) is a pure removal placed inside it, or
   removed a reader prefix placed directly before it."
  [added removals [start end]]
  (boolean (or (some #(<= start % end) added)
               (some (fn [{:keys [after replaced? lines]}]
                       (or (and (not replaced?) (<= start after) (< after end))
                           (and (= after (dec start)) (some reader-prefix? lines))))
                     removals))))

(defn ^{:stratum 1} form-spans
  "[first-line last-line] of each top-level form in CONTENT, in order, or
   an :invalid-input anomaly when edamame cannot read CONTENT. Every syntax
   is enabled, aliases stay unresolved but distinct, and reader
   conditionals are kept whole. CONTENT is read once with its values and
   tagged literals kept whole, so an invalid literal such as a duplicate
   map key is refused, then again with each form read as its location:
   locations alone would hide duplicates."
  [content]
  (try+
    (let [opts {:all          true
                :read-cond    :preserve
                :auto-resolve alias-namespace}]
      (edamame/parse-string-all content (assoc opts :readers tagged-reader))
      (mapv (juxt :row :end-row)
            (edamame/parse-string-all content (assoc opts
                                                     :readers     (constantly identity)
                                                     :postprocess :loc))))
    (catch [:type :edamame/error] _
      (anomaly/exception-anomaly :invalid-input (msg/t :edited-forms/unreadable)
                                 (:throwable &throw-context)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} edited-lines
  "The set of CONTENT's line numbers a change edits, given the set of lines
   it ADDED and its REMOVALS (`git-change/removals`). In a form file every
   top-level form the change edits (see the namespace docstring) is edited
   whole; an anomaly when that content cannot be read."
  [path content added removals]
  (if-not (form-file? path)
    (set added)
    (let [spans (form-spans content)]
      (if (anomaly/anomaly? spans)
        spans
        (into (set added)
              (comp (filter #(edited-span? added removals %))
                    (mapcat span-lines))
              spans)))))
