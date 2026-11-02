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
(ns ai.miniforge.deliberation-harness.fixtures
  "Synthetic development tasks and their deterministic scoring. A task's
   sealed reference and distractors stay outside every model request."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.codec :as codec]
   [ai.miniforge.deliberation-harness.messages :as messages]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} tasks-resource
  "Classpath resource holding the synthetic tasks and their sealed answers,
   packaged with the component so a run never reads them from the working tree."
  "config/deliberation-harness/tasks.edn")

(def ^{:stratum 0} public-keys
  "The only task fields a model request may carry."
  [:id :version :synthetic? :brief])

(defn ^{:stratum 0} public-task [task]
  (select-keys task public-keys))

(defn- ^{:stratum 0} checks [task solution]
  (into {} (map (fn [[k value]] [k (and (map? solution) (contains? solution k)
                                        (= value (get solution k)))]))
        (:reference task)))

(defn- ^{:stratum 0} complete-inspection? [task inspected]
  (= (set (keys (:experiments task))) (set inspected)))

;; True when `task` has a non-empty reference map, its experiments (if any)
;; are a map, and `inspected` is a collection of experiment ids (or nil).
(defn- ^{:stratum 0} scorable?
  [task inspected]
  (and (map? (:reference task)) (seq (:reference task))
       (or (nil? (:experiments task)) (map? (:experiments task)))
       (or (nil? inspected) (coll? inspected))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} tasks
  "The synthetic task package, or an anomaly when the resource is missing or
   unreadable."
  []
  (if-let [resource (io/resource tasks-resource)]
    (codec/read-source resource)
    (anomaly/anomaly :not-found (messages/system-t :anomaly/missing-resource)
                     {:resource tasks-resource})))

(defn ^{:stratum 1} score
  "Score `solution` for `task` given the experiment ids `inspected`. Success
   needs every reference choice and every available observation inspected.
   An `:invalid-input` anomaly when the task has no reference to score
   against or `inspected` is not a collection."
  [task solution inspected]
  (if-not (scorable? task inspected)
    (anomaly/anomaly :invalid-input (messages/system-t :anomaly/unscorable-task)
                     {:task (:id task)})
    (let [results (checks task solution)
          inspected? (complete-inspection? task inspected)
          correct (count (filter true? (vals results)))]
      {:success (if (and inspected? (every? true? (vals results))) 1 0)
       :checks results
       :evidence-complete? inspected?
       :partial (/ correct (count results))})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} fixture-defects
  "Every task whose reference fails, or whose distractor passes, its own
   scoring — empty when the package can discriminate."
  [task-package]
  (for [task task-package
        [candidate expected] (cons [(:reference task) 1]
                                   (map vector (:distractors task) (repeat 0)))
        :let [inspected (keys (:experiments task))]
        :when (not= expected (:success (score task candidate inspected)))]
    {:task (:id task) :candidate candidate}))
