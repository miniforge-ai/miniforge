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
(ns ai.miniforge.agent.implementer.repair
  "Repair of an implementer code artifact: duplicate paths, placeholder
   content, and missing required fields."
  (:require
   [ai.miniforge.agent.implementer.code :as impl-code]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} deduplicate-files
  "Deduplicate file entries by path, keeping last occurrence."
  [files]
  (let [seen (atom #{})
        deduped (reduce (fn [acc f]
                          (if (contains? @seen (:path f))
                            acc
                            (do (swap! seen conj (:path f))
                                (conj acc f))))
                        []
                        (reverse files))]
    (vec (reverse deduped))))

;; Artifact repair
(defn- ^{:stratum 0} repair-placeholder-content
  "Build minimal non-empty placeholder content for an otherwise blank file."
  [path]
  (let [comment-prefix (case (some-> path impl-code/extract-extension str/lower-case)
                         "clj" ";;"
                         "cljc" ";;"
                         "cljs" ";;"
                         "edn" ";;"
                         "js" "//"
                         "ts" "//"
                         "java" "//"
                         "go" "//"
                         "rs" "//"
                         "swift" "//"
                         "py" "#"
                         "rb" "#"
                         "sh" "#"
                         "yml" "#"
                         "yaml" "#"
                         "toml" "#"
                         nil)]
    (if comment-prefix
      (str comment-prefix " TODO: replace generated placeholder for " path "\n")
      (str "TODO: replace generated placeholder for " path "\n"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} ensure-required-fields
  "Ensure a file entry has content and action, filling blank creates with a placeholder.
  Drops entries with no path."
  [f]
  (when (:path f)
    (let [file (cond-> f
                 (nil? (:content f)) (assoc :content "")
                 (not (:action f))   (assoc :action :create))]
      (cond-> file
        (and (= :create (:action file))
             (str/blank? (:content file)))
        (assoc :content (repair-placeholder-content (:path file)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} repair-code-artifact
  "Attempt to repair a code artifact based on validation errors."
  [artifact errors _context]
  (let [repaired (-> artifact
                     (update :code/id #(or % (random-uuid)))
                     (update :code/files #(or % []))
                     (update :code/files #(filterv some? (map ensure-required-fields %)))
                     (update :code/files deduplicate-files))]
    {:status :success
     :output repaired
     :repairs-made (when (not= artifact repaired)
                     {:original-errors errors})}))
