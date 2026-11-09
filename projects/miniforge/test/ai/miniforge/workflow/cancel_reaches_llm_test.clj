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
(ns ai.miniforge.workflow.cancel-reaches-llm-test
  "Recovery scenario C4, end to end: cancelling a run while its phase
   waits on the agent CLI kills the CLI and everything it started, and the
   runner records the stop. The runner iteration and the LLM client's
   non-streaming path are real; the phase step calls a stub CLI, a shell
   that starts a 600 s child and records both pids."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.llm.protocols.impl.llm-client :as llm-impl]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.execution :as exec]
   [ai.miniforge.workflow.monitoring :as monitoring]
   [ai.miniforge.workflow.runner-checkpoint :as runner-checkpoint]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-iteration :as runner-iteration]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} alive? [pid]
  (let [handle (java.lang.ProcessHandle/of pid)]
    (and (.isPresent handle)
         (let [^java.lang.ProcessHandle process (.get handle)]
           (and (.isAlive process) (.isPresent (.command (.info process))))))))

(defn- ^{:stratum 0} wait-until [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(defn- ^{:stratum 0} recorded-pids [pid-file]
  (when (fs/exists? pid-file)
    (mapv parse-long (str/split-lines (slurp pid-file)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} cancel-kills-the-agent-cli-tree-test
  (fs/with-temp-dir [dir {:prefix "cancel-llm-"}]
    (let [pid-file (str (fs/path dir "pids"))
          cli      ["sh" "-c" (str "sleep 600 & echo $! > " pid-file ".tmp; echo $$ >> " pid-file ".tmp; "
                                   "mv " pid-file ".tmp " pid-file "; wait")]
          cs       (es/create-control-state)
          context  (ctx/create-context {:workflow/id :cancel-llm :workflow/version "1.0.0"
                                        :workflow/pipeline [{:phase :implement}]}
                                       {:task "Test"} {})
          llm-call (promise)
          step     (fn [_ c & _]
                     (deliver llm-call (llm-impl/default-exec-fn cli {:workdir (str dir)}))
                     (ctx/transition-to-failed c))
          run      (future
                     (with-redefs-fn {#'monitoring/check-workflow-supervision (constantly {:status :continue})
                                      #'exec/execute-phase-step step
                                      #'env/persist-workspace-at-phase-boundary! (fn [_ _] nil)
                                      #'runner-checkpoint/checkpoint! identity}
                       #(runner-iteration/execute-single-iteration [] context {} 0 cs)))
          pids     (when (wait-until 10000 #(= 2 (count (recorded-pids pid-file))))
                     (recorded-pids pid-file))]
      (try
        (is (= 2 (count pids)) "the stub CLI started its child")
        (es/cancel! cs)
        (let [result (deref run 10000 ::still-running)]
          (is (not= ::still-running result) "the phase's LLM call ends")
          (is (= :cancelled (get-in (deref llm-call 0 nil) [:timeout :type])))
          (is (some #{:dashboard-stop} (map :type (:execution/errors result)))))
        (is (wait-until 5000 #(not-any? alive? pids)) "no process of the CLI tree is left")
        (finally
          (future-cancel run)
          (doseq [pid pids]
            (let [h (java.lang.ProcessHandle/of pid)]
              (when (.isPresent h) (.destroyForcibly ^java.lang.ProcessHandle (.get h))))))))))
