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

   A top-level form is unedited when the file at the task base has a
   top-level form with the same source text, each base form vouching for
   one form only; every other form is edited whole. Matching text rather
   than diff positions covers every way a form can change: an edited body,
   a removed `^:private` or `#_` prefix, an unwrapped `(comment ...)`, or a
   pasted copy. An added line outside every form is edited on its own.
   Other files are edited exactly on their added lines.

   Forms are located with edamame, which reads past aliases, reader
   conditionals and tagged literals it cannot resolve, so one unknown
   `::alias/kw` does not end the read. Content it cannot read is an
   anomaly: nothing in it can be placed inside or outside an edit."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.gate.messages :as msg]
            [clojure.string :as str]
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

(defn- ^{:stratum 0} span-text
  "The source text LINES hold between an edamame location's start and end
   (1-based columns, the end column exclusive)."
  [lines {:keys [row col end-row end-col]}]
  (let [rows (subvec lines (dec row) (min end-row (count lines)))
        final (dec (count rows))]
    (str/join "\n" (map-indexed (fn [i line]
                                  (subs line
                                        (if (zero? i) (min (dec col) (count line)) 0)
                                        (if (= i final) (min (dec end-col) (count line)) (count line))))
                                rows))))

(defn- ^{:stratum 0} span-lines
  "Every line number of FORM, from its :start to its :end."
  [{:keys [start end]}]
  (range start (inc end)))

(defn- ^{:stratum 0} within-form?
  "True when LINE lies inside one of FORMS."
  [forms line]
  (boolean (some (fn [{:keys [start end]}] (<= start line end)) forms)))

(defn- ^{:stratum 0} unmatched-forms
  "FORMS whose :text matches no form of BASE-FORMS, each base form matching
   at most one of FORMS, in order."
  [forms base-forms]
  (first (reduce (fn [[out counts] {:keys [text] :as form}]
                   (if (pos? (get counts text 0))
                     [out (update counts text dec)]
                     [(conj out form) counts]))
                 [[] (frequencies (map :text base-forms))]
                 forms)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} form-file?
  "True when PATH's content is read as top-level forms."
  [path]
  (contains? form-extensions (second (re-find #"\.([^./]+)$" (str path)))))

(defn ^{:stratum 1} form-spans
  "{:start :end :text} for each top-level form in CONTENT, in order: its
   first and last line and its source text. An :invalid-input anomaly when
   edamame cannot read CONTENT. Every syntax is enabled, aliases stay
   unresolved but distinct, and reader conditionals are kept whole.
   CONTENT is read once with its values and tagged literals kept whole, so
   an invalid literal such as a duplicate map key is refused, then again
   with each form read as its location: locations alone would hide
   duplicates."
  [content]
  (try+
    (let [opts  {:all          true
                 :read-cond    :preserve
                 :auto-resolve alias-namespace}
          lines (vec (str/split-lines content))]
      (edamame/parse-string-all content (assoc opts :readers tagged-reader))
      (mapv (fn [loc] {:start (:row loc) :end (:end-row loc) :text (span-text lines loc)})
            (edamame/parse-string-all content (assoc opts
                                                     :readers     (constantly identity)
                                                     :postprocess :loc))))
    (catch [:type :edamame/error] _
      (anomaly/exception-anomaly :invalid-input (msg/t :edited-forms/unreadable)
                                 (:throwable &throw-context)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} edited-lines
  "The set of CONTENT's line numbers a change edits, given BASE (PATH's
   content at the task base, nil when the file is new) and the set of
   lines the change ADDED. In a form file, the lines of every form BASE has
   no matching form for (see the namespace docstring) plus the added lines
   outside every form; an anomaly when CONTENT or BASE cannot be read. In
   any other file, the added lines."
  [path content base added]
  (if-not (form-file? path)
    (set added)
    (let [forms      (form-spans content)
          base-forms (if base (form-spans base) [])]
      (cond
        (anomaly/anomaly? forms)      forms
        (anomaly/anomaly? base-forms) base-forms
        :else (into (set (remove #(within-form? forms %) added))
                    (mapcat span-lines)
                    (unmatched-forms forms base-forms))))))
