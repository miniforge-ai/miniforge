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

(ns ai.miniforge.reporting.views.edn
  "EDN data rendering.

   Provides pretty-printed EDN output for structured data."
  (:require [clojure.pprint]))

;------------------------------------------------------------------------------ Layer 0
;; EDN rendering

(defn render-edn
  "Render arbitrary data as pretty-printed EDN.

   Args:
     data - Any Clojure data structure

   Returns:
     Pretty-printed EDN string."
  [data]
  (with-out-str
    (clojure.pprint/pprint data)))

(comment
  ;; Test EDN rendering
  (println (render-edn {:status :running
                        :workflows [{:id 1 :name "Pipeline"}
                                   {:id 2 :name "Deploy"}]
                        :meta {:cycle 42}}))

  (println (render-edn [1 2 3 4 5]))

  :end)
