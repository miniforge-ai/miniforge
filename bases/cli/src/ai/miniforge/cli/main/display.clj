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
(ns ai.miniforge.cli.main.display
  "Terminal styling and generic entity/detail rendering for CLI output.

   Layer 0: ANSI color table and data-driven field/section renderers
   Layer 1: ANSI styling primitive
   Layer 2: Styled print helpers and the composite detail-view renderer

   Classified-error display (headers, context, retry recommendation, and
   the `print-classified-error` composite) lives in
   `ai.miniforge.cli.main.display.classified-error` (rule 210: the
   combined namespace measured 5 real layers, max 3)."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; ANSI styling primitives
(def ^{:stratum 0} ansi-colors
  {:red     "31"
   :green   "32"
   :yellow  "33"
   :blue    "34"
   :magenta "35"
   :cyan    "36"
   :white   "37"})

;; Data-driven detail rendering
(defn ^{:stratum 0} render-fields
  "Render entity fields from a data-driven spec.
   Each field is [data-key message-key & [opts-map]].
   Skips nil values. Supports :default, :transform, and :param (default :value)."
  [entity fields]
  (doseq [[data-key msg-key & [{:keys [default transform param]}]] fields]
    (let [raw-val (get entity data-key)
          val     (if (nil? raw-val) default raw-val)]
      (when-not (nil? val)
        (let [display-val (if transform (transform val) (str val))
              param-key   (or param :value)]
          (println (messages/t msg-key {param-key display-val})))))))

(defn ^{:stratum 0} render-section
  "Render a titled section with child entries.
   section: {:key K :header H :entry E :entry-fn (fn [item] -> params) :max N}"
  [entity {:keys [key header entry entry-fn max]}]
  (when-let [items (seq (get entity key))]
    (when header
      (println (messages/t header {:count (count items)})))
    (when entry
      (doseq [item (cond->> items max (take max))]
        (println (messages/t entry (if entry-fn (entry-fn item) {:value (str item)})))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} style
  "Apply terminal styling using ANSI escape codes."
  [text & {:keys [foreground bold]}]
  (let [codes (cond-> []
                bold (conj "1")
                foreground (conj (get ansi-colors foreground "37")))]
    (if (seq codes)
      (str "\033[" (str/join ";" codes) "m" text "\033[0m")
      text)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} print-error [msg]
  (println (style (messages/t :classified-error/error-prefix
                              {:message msg})
                  :foreground :red)))

(defn ^{:stratum 2} print-success [msg]
  (println (style msg :foreground :green)))

(defn ^{:stratum 2} print-info [msg]
  (println (style msg :foreground :cyan)))

(defn ^{:stratum 2} render-detail
  "Render a complete detail view: header + fields + sections."
  [{:keys [header header-params fields sections]} entity]
  (println)
  (when header
    (println (style (messages/t header header-params) :foreground :cyan :bold true)))
  (render-fields entity fields)
  (doseq [section sections]
    (render-section entity section))
  (println))
