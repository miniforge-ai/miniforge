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
(ns ai.miniforge.event-stream.layout-reader-test
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.layout-reader :as sut]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} the-start-event-is-found-when-the-run-moves-during-the-lookup
  (let [dir        (doto (io/file (System/getProperty "java.io.tmpdir") (str "layout-reader-" (random-uuid)))
                     .mkdirs)
        stream     (es/create-event-stream
                    {:config {:observability {:event-sinks [{:type :file :base-dir (str dir)}]}}})
        wf-id      (random-uuid)
        list-files sut/event-files
        moved?     (atom false)]
    (es/publish! stream (assoc (es/create-envelope stream :workflow/started wf-id "started")
                               :workflow/spec {:name "Moving"}))
    ;; The first lookup resolves live/, then the run moves to archived/ before it lists.
    (with-redefs [sut/event-files (fn [d]
                                    (when (compare-and-set! moved? false true)
                                      (.mkdirs (io/file dir "archived"))
                                      (.renameTo (io/file dir "live" (str wf-id))
                                                 (io/file dir "archived" (str wf-id))))
                                    (list-files d))]
      (is (= "Moving" (get-in (sut/start-event dir wf-id) [:workflow/spec :name]))))))
