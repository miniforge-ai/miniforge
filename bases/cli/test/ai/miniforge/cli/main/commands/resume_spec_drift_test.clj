;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.resume-spec-drift-test
  "`mf resume` checks the spec a run started from against the hash its
   manifest recorded: a changed spec is refused unless --force, the check
   holds when the snapshot cannot be restored, an unknown hash is said out
   loud, and a new attempt keeps the recorded spec."
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
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow-resume.interface :as wr]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private events-only
  "A run reconstructed from events alone: no snapshot restored."
  {:completed-phases [:plan] :phase-results {:plan {}} :event-count 3
   :completed-dag-tasks #{} :completed-dag-artifacts []
   :workflow-spec {:name "canonical-sdlc" :version "1.0.0"}})

(defn- ^{:stratum 0} manifest-for [spec content]
  {:workflow/spec-path spec :workflow/spec-hash (config/sha256-hex content)})

(defn- ^{:stratum 0} resume-with
  "Resume `reconstructed`, whose manifest on disk reads as `manifest`, with
   the runtime stubbed. Returns {:run-opts :thrown :out}."
  [reconstructed manifest opts]
  (let [run-opts (atom nil)
        thrown (atom nil)
        out (with-redefs [wr/reconstruct-context (fn [_ _] reconstructed)
                          workflow/read-checkpoint-manifest (fn [_] manifest)
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
                          sut/run-pipeline (fn [_ _ o] (reset! run-opts o) {:execution/status :completed})]
              (with-out-str
                (try (sut/resume-workflow (str (random-uuid)) (assoc opts :quiet true))
                     (catch clojure.lang.ExceptionInfo e (reset! thrown e)))))]
    {:run-opts @run-opts :thrown @thrown :out out}))

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
    (let [spec (str (fs/path dir "task.spec.edn"))
          manifest (manifest-for spec "original")]
      (spit spec "edited")
      (testing "without --force nothing runs and the error names --force"
        (let [{:keys [run-opts thrown]} (resume-with events-only manifest {})]
          (is (nil? run-opts))
          (is (str/includes? (ex-message thrown) "--force"))))
      (testing "with --force it runs, after a warning"
        (let [{:keys [run-opts out]} (resume-with events-only manifest {:force true})]
          (is (some? run-opts))
          (is (str/includes? out "--force"))))
      (testing "an unchanged spec resumes without a word"
        (let [{:keys [run-opts out]} (resume-with events-only (manifest-for spec "edited") {})]
          (is (some? run-opts))
          (is (not (str/includes? out "spec"))))))))

(deftest ^{:stratum 1} the-check-holds-when-the-snapshot-is-not-restored
  ;; reconstruct-context carries no :checkpoint-manifest (snapshot missing
  ;; or unreadable, event fallback); the manifest on disk still reads.
  (fs/with-temp-dir [dir {:prefix "resume-spec-"}]
    (let [spec (str (fs/path dir "task.spec.edn"))]
      (spit spec "edited")
      (is (some? (:thrown (resume-with events-only (manifest-for spec "original") {})))))))

(deftest ^{:stratum 1} an-unknown-hash-is-said-not-passed
  (testing "no readable manifest"
    (let [{:keys [run-opts out]} (resume-with events-only nil {})]
      (is (some? run-opts))
      (is (str/includes? out "cannot check that the spec"))))
  (testing "a spec run whose manifest recorded no hash"
    (let [{:keys [out]} (resume-with (assoc events-only :machine-snapshot
                                            {:execution/input {:spec/path "work/x.spec.edn"}})
                                     {:workflow/id 1} {})]
      (is (str/includes? out "no spec hash was recorded")))))

(deftest ^{:stratum 1} the-resumed-run-carries-the-recorded-spec
  (fs/with-temp-dir [dir {:prefix "resume-spec-"}]
    (let [spec (str (fs/path dir "task.spec.edn"))
          manifest (manifest-for spec "same")]
      (spit spec "same")
      (is (= manifest (:checkpoint/spec (:run-opts (resume-with events-only manifest {}))))))))

(comment ::resume-spec-drift)
