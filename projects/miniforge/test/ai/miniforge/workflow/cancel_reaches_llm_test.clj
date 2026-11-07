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
   waits on the agent CLI kills the CLI and everything it started. The
   LLM client's non-streaming path is real; the CLI is a stub shell that
   starts a 600 s child and records both pids."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.llm.protocols.impl.llm-client :as llm-impl]
   [ai.miniforge.workflow.stop-signal :as stop-signal]
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

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} cancel-kills-the-agent-cli-tree-test
  (fs/with-temp-dir [dir {:prefix "cancel-llm-"}]
    (let [pid-file (str (fs/path dir "pids"))
          cli      ["sh" "-c" (str "sleep 600 & echo $! > " pid-file "; echo $$ >> " pid-file "; wait")]
          cs       (es/create-control-state)
          phase    (future
                     (try (stop-signal/interrupting-on-stop
                           cs #(llm-impl/default-exec-fn cli {:workdir (str dir)}))
                          (catch InterruptedException _ :interrupted)))
          pids     (when (wait-until 10000 #(= 2 (count (some-> (fs/file pid-file)
                                                               (as-> f (when (fs/exists? f) (slurp f)))
                                                               str/split-lines))))
                     (mapv parse-long (str/split-lines (slurp pid-file))))]
      (try
        (is (= 2 (count pids)) "the stub CLI started its child")
        (es/cancel! cs)
        (is (= :interrupted (deref phase 10000 ::still-running)) "the phase's LLM call ends")
        (is (wait-until 5000 #(not-any? alive? pids)) "no process of the CLI tree is left")
        (finally
          (doseq [pid pids]
            (let [h (java.lang.ProcessHandle/of pid)]
              (when (.isPresent h) (.destroyForcibly ^java.lang.ProcessHandle (.get h))))))))))
