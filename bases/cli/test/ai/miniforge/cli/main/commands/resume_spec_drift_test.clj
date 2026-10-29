;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.resume-spec-drift-test
  "`mf resume` refuses a run whose spec changed since it started, unless
   --force; the flag is accepted on the command line."
  (:require [ai.miniforge.automation-edge-correlator.interface :as correlator]
            [ai.miniforge.cli.main :as main]
            [ai.miniforge.cli.main.commands.resume :as sut]
            [ai.miniforge.cli.main.commands.shared :as shared]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.workflow-runner.context :as context]
            [ai.miniforge.cli.workflow-runner.control :as control]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workflow-resume.interface :as wr]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resume-with
  "Resume a run whose manifest recorded `spec` with hash of `recorded`
   content, the runtime stubbed. Returns {:ran? :thrown :out}."
  [spec recorded opts]
  (let [ran? (atom false)
        thrown (atom nil)
        out (with-redefs [wr/reconstruct-context
                          (fn [_ _]
                            {:completed-phases [:plan] :phase-results {:plan {}} :event-count 0
                             :completed-dag-tasks #{} :completed-dag-artifacts []
                             :workflow-spec {:name "canonical-sdlc" :version "1.0.0"}
                             :checkpoint-manifest {:workflow/spec-path spec
                                                   :workflow/spec-hash (config/sha256-hex recorded)}})
                          context/create-llm-client (fn [_ _ _] :llm-client)
                          es/create-event-stream (fn [] :event-stream)
                          supervisory/attach! (fn [_] nil)
                          correlator/attach! (fn [_] nil)
                          control/register-workflow-control! (fn [_ _ _] nil)
                          control/release-workflow-control! (fn [_] nil)
                          display/print-error (fn [& _] nil)
                          sut/load-workflow (fn [_ _ _]
                                              {:workflow {:workflow/id :canonical-sdlc
                                                          :workflow/version "1.0.0"
                                                          :workflow/pipeline [{:phase :plan}
                                                                              {:phase :implement}]}})
                          sut/run-pipeline (fn [& _] (reset! ran? true) {:execution/status :completed})]
              (with-out-str
                (try (sut/resume-workflow (str (random-uuid)) (assoc opts :quiet true))
                     (catch clojure.lang.ExceptionInfo e (reset! thrown e)))))]
    {:ran? @ran? :thrown @thrown :out out}))

(deftest ^{:stratum 0} resume-accepts-force
  (let [seen (atom nil)]
    (with-redefs [sut/resume-workflow (fn [_ opts] (reset! seen opts) {:execution/status :completed})
                  shared/exit! (fn [_] nil)]
      (binding [*err* (java.io.StringWriter.)]
        (with-out-str (main/-main "resume" "wf-1" "--force"))))
    (is (true? (:force @seen)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-changed-spec-refuses-the-resume
  (fs/with-temp-dir [dir {:prefix "resume-spec-"}]
    (let [spec (str (fs/path dir "task.spec.edn"))]
      (spit spec "edited")
      (testing "without --force nothing runs and the error names --force"
        (let [{:keys [ran? thrown]} (resume-with spec "original" {})]
          (is (false? ran?))
          (is (some? thrown))
          (is (str/includes? (ex-message thrown) "--force"))))
      (testing "with --force it runs, after a warning"
        (let [{:keys [ran? thrown out]} (resume-with spec "original" {:force true})]
          (is (true? ran?))
          (is (nil? thrown))
          (is (str/includes? out "--force"))))
      (testing "an unchanged spec resumes without a word"
        (is (true? (:ran? (resume-with spec "edited" {}))))))))

(comment ::resume-spec-drift)
