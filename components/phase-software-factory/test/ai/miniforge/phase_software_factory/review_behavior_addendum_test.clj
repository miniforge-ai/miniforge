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
(ns ai.miniforge.phase-software-factory.review-behavior-addendum-test
  "Which rules review asks for: the reviewer's behavior addendum is filtered
   by the files implement changed, so rules scoped to those files by glob
   reach the reviewer. Review used to pass no file path, and every
   glob-scoped rule failed to match the empty path."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-software-factory.review]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private build-review-task
  #'ai.miniforge.phase-software-factory.review/build-review-task)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} behavior-filter-context
  "The phase and context review hands load-and-filter-behaviors for `ctx`."
  [ctx]
  (let [seen (atom nil)]
    (with-redefs [phase/load-and-filter-behaviors
                  (fn [phase-kw context] (reset! seen [phase-kw context]) nil)]
      (build-review-task ctx))
    @seen))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} reviewer-rules-follow-the-changed-files-test
  (testing "review filters its rules by the paths implement wrote"
    (let [[phase-kw context]
          (behavior-filter-context
           {:execution/input {:title "t" :description "d" :intent {:intent/type :modify}}
            :execution/phase-results
            {:implement {:artifact {:code/files [{:path "src/core.clj" :content "(ns core)"
                                                  :action :create}
                                                 {:path "README.md" :content "# r"
                                                  :action :modify}]}}}})]
      (is (= :review phase-kw))
      (is (= ["src/core.clj" "README.md"] (get-in context [:artifact :artifact/paths])))
      (is (= {:intent/type :modify} (get-in context [:task :task/intent]))))))
