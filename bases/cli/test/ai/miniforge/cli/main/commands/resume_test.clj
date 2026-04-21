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

(ns ai.miniforge.cli.main.commands.resume-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [ai.miniforge.cli.main.commands.resume :as sut]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]))

(deftest resolve-resume-workflow-test
  (testing "recorded workflow spec wins over configured fallback"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile]
                    (throw (ex-info "should not be called" {})))]
      (is (= {:workflow-type :financial-etl
              :workflow-version "1.2.3"}
             (sut/resolve-resume-workflow
              {:workflow-spec {:name "financial-etl"
                               :version "1.2.3"}})))))

  (testing "missing workflow spec falls back to app-configured default profile"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [profile]
                    (is (= :default profile))
                    :lean-sdlc-v1)]
      (is (= {:workflow-type :lean-sdlc-v1
              :workflow-version "latest"}
             (sut/resolve-resume-workflow {})))))

  (testing "missing configured fallback raises a clear error"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile] nil)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Could not resolve a workflow type for resume"
           (sut/resolve-resume-workflow {}))))))

;------------------------------------------------------------------------------ Layer 1
;; read-event-file — bug fix coverage

(defn- with-temp-events-dir [body-fn]
  (let [base (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-resume-test-" (random-uuid)))
               .mkdirs)]
    (try
      (body-fn base)
      (finally
        (doseq [^java.io.File f (reverse (file-seq base))]
          (.delete f))))))

(defn- write-event! [^java.io.File dir filename event-map]
  (spit (io/file dir filename) (json/generate-string event-map)))

(deftest read-event-file-reads-per-event-json-from-workflow-dir-test
  ;; Regression guard for the iter-20 resume bug. Before this fix,
  ;; read-event-file looked for a single {workflow-id}.edn file that
  ;; was never written (the sink writes one .json per event to a dir),
  ;; so `mf run --resume <id>` always threw :anomalies/not-found.
  (with-temp-events-dir
    (fn [base-dir]
      (let [wf-id (str (random-uuid))
            wf-dir (doto (io/file base-dir wf-id) .mkdirs)]
        (write-event! wf-dir "20260420T000001Z-a.json"
                      {"~:event/type" "~:workflow/started"
                       "~:workflow/id" (str "~u" wf-id)})
        (write-event! wf-dir "20260420T000002Z-b.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:plan"
                       "~:phase/outcome" "~:success"})
        (testing "reads events in timestamp order with transit prefixes stripped"
          (with-redefs [sut/events-dir (.getPath base-dir)]
            (let [events (sut/read-event-file wf-id)]
              (is (= 2 (count events)))
              (is (= :workflow/started (:event/type (first events))))
              (is (= :workflow/phase-completed (:event/type (second events))))
              (is (= :plan (:workflow/phase (second events))))
              (is (= :success (:phase/outcome (second events)))))))))))

(deftest read-event-file-missing-workflow-returns-nil-test
  ;; The CLI wrapper now returns nil for missing workflows; the
  ;; user-facing :anomalies/not-found comes from the workflow-resume
  ;; component's `reconstruct-context` when callers use that path.
  (with-temp-events-dir
    (fn [base-dir]
      (with-redefs [sut/events-dir (.getPath base-dir)]
        (is (nil? (sut/read-event-file (str (random-uuid)))))))))
