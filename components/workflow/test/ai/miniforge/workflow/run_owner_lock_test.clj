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
(ns ai.miniforge.workflow.run-owner-lock-test
  "Recovery scenario C3: one live process per run. A second start of a
   running run (a resume, a launcher retry) is refused while the owner
   lock is held, in this process or another, and allowed once the owner
   is gone."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.run-owner-lock :as owner]
   [ai.miniforge.workflow.runner :as runner]
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private holder-script
  "bb program for a second process: lock argv[0], print \"locked\", wait
   for a line on stdin, then exit, which releases the lock."
  (str "(let [[f] *command-line-args*"
       "      ch (java.nio.channels.FileChannel/open (java.nio.file.Paths/get f (make-array String 0))"
       "           (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE"
       "                                                 java.nio.file.StandardOpenOption/WRITE]))"
       "      held (.tryLock ch)]"
       "  (println (if held \"locked\" \"busy\")) (flush) (read-line))"))

(deftest ^{:stratum 0} owner-lock-in-this-process-test
  (fs/with-temp-dir [root {:prefix "run-owner-"}]
    (let [id   (random-uuid)
          held (owner/acquire! root id)]
      (try
        (is (not (anomaly/anomaly? held)))
        (testing "a second claim is refused, naming the owner"
          (let [again (owner/acquire! root id)]
            (is (= :conflict (:anomaly/type again)))
            (is (= (.pid (java.lang.ProcessHandle/current))
                   (get-in again [:anomaly/data :run/owner :owner/pid])))))
        (testing "the probe reports the live owner"
          (is (= (str id) (:owner/run-id (owner/owner root id)))))
        (finally (owner/release! held)))
      (testing "released: no owner, and the run can be claimed again"
        (is (nil? (owner/owner root id)))
        (let [next-claim (owner/acquire! root id)]
          (is (not (anomaly/anomaly? next-claim)))
          (owner/release! next-claim))))))

(deftest ^{:stratum 0} with-run-owner-does-not-run-a-claimed-run-test
  (fs/with-temp-dir [root {:prefix "run-owner-"}]
    (let [id   (random-uuid)
          held (owner/acquire! root id)
          ran  (atom false)]
      (try
        (is (= :conflict (try+ (owner/with-run-owner root id #(reset! ran true))
                               :ran
                               (catch [:anomaly/type :conflict] _ :conflict))))
        (is (false? @ran))
        (finally (owner/release! held)))
      (is (= :done (owner/with-run-owner root id (constantly :done))))
      (is (= :fresh (owner/with-run-owner root nil (constantly :fresh)))
          "a run id minted fresh needs no lock"))))

(deftest ^{:stratum 0} run-pipeline-refuses-a-running-run-test
  (fs/with-temp-dir [root {:prefix "run-owner-pipeline-"}]
    (let [id       (random-uuid)
          held     (owner/acquire! root id)
          acquired (atom false)]
      (try
        (with-redefs-fn {#'ai.miniforge.workflow.runner/acquire-environment
                         (fn [& _] (reset! acquired true) (throw (ex-info "must not start" {})))}
          #(is (= :conflict
                  (try+ (runner/run-pipeline {:workflow/id :owner-test :workflow/pipeline []}
                                             {} {:workflow-id id :checkpoint/root (str root)})
                        :started
                        (catch [:anomaly/type :conflict] _ :conflict)))))
        (is (false? @acquired) "nothing is acquired for a refused run")
        (finally (owner/release! held))))))

(def ^{:stratum 0} ^:private probe-script
  "bb program: try to lock argv[0], then release; print \"busy\" when
   another process holds it, else \"free\"."
  (str "(let [ch (java.nio.channels.FileChannel/open (java.nio.file.Paths/get (first *command-line-args*) (make-array String 0))"
       "          (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/WRITE]))]"
       "  (println (if (try (some? (.tryLock ch)) (finally (.close ch))) \"free\" \"busy\")))"))

(deftest ^{:stratum 0} run-pipeline-locks-the-run-it-executes-test
  (fs/with-temp-dir [root {:prefix "run-owner-pipeline-"}]
    (testing "a snapshot-only restore claims the snapshot's run"
      (let [id   (random-uuid)
            held (owner/acquire! root id)]
        (try
          (is (= :conflict
                 (try+ (runner/run-pipeline {:workflow/id :owner-test :workflow/pipeline []} {}
                                            {:resume-machine-snapshot {:execution/id id}
                                             :checkpoint/root (str root)})
                       :started
                       (catch [:anomaly/type :conflict] _ :conflict))))
          (finally (owner/release! held)))))
    (testing "a run given no id holds the lock on the id it is given"
      (let [seen (atom nil)]
        (with-redefs-fn {#'ai.miniforge.workflow.runner/acquire-environment
                         (fn [_ _ opts]
                           (reset! seen {:id (:workflow-id opts)
                                         :owner (owner/owner root (:workflow-id opts))})
                           (throw (ex-info "stop here" {})))}
          #(try (runner/run-pipeline {:workflow/id :owner-test :workflow/pipeline []} {}
                                     {:checkpoint/root (str root)})
                (catch clojure.lang.ExceptionInfo _ nil)))
        (is (uuid? (:id @seen)))
        (is (= (str (:id @seen)) (get-in @seen [:owner :owner/run-id])))
        (is (nil? (owner/owner root (:id @seen))) "released when the run ends")))
    (testing "a snapshot without phase results is not restored: the options' root is locked"
      (let [id (random-uuid)
            snapshot-root (str (fs/create-dirs (fs/path root "snapshot-root")))
            opts-root (str (fs/create-dirs (fs/path root "opts-root")))
            seen (atom nil)]
        (with-redefs-fn {#'ai.miniforge.workflow.runner/acquire-environment
                         (fn [_ _ _]
                           (reset! seen {:opts (owner/owner opts-root id)
                                         :snapshot (owner/owner snapshot-root id)})
                           (throw (ex-info "stop here" {})))}
          #(try (runner/run-pipeline {:workflow/id :owner-test :workflow/pipeline []} {}
                                     {:resume-machine-snapshot {:execution/id id
                                                                :execution/checkpoint-root snapshot-root}
                                      :checkpoint/root opts-root})
                (catch clojure.lang.ExceptionInfo _ nil)))
        (is (some? (:opts @seen)))
        (is (nil? (:snapshot @seen)))))))

(deftest ^{:stratum 0} owner-file-is-stable-through-a-symlinked-parent-test
  (fs/with-temp-dir [dir {:prefix "run-owner-link-"}]
    (let [real  (fs/create-dirs (fs/path dir "real"))
          alias (fs/create-sym-link (fs/path dir "alias") real)
          root  (fs/path alias "checkpoints")
          id    (random-uuid)
          before (owner/owner-file root id)
          held  (owner/acquire! root id)]
      (try
        (is (= (str before) (str (owner/owner-file root id)))
            "the same file before and after the root exists")
        (is (= :conflict (:anomaly/type (owner/acquire! root id)))
            "a second local claim through the alias is answered by the registry")
        (finally (owner/release! held))))))

(deftest ^{:stratum 0} owner-file-is-stable-through-parent-traversal-test
  (fs/with-temp-dir [dir {:prefix "run-owner-dots-"}]
    (let [root   (str (fs/path dir "unused" ".." "checkpoints"))
          id     (random-uuid)
          before (owner/owner-file root id)
          held   (owner/acquire! root id)]
      (try
        (fs/create-dirs (fs/path dir "checkpoints"))
        (is (= (str before) (str (owner/owner-file root id)))
            "the same file before and after the root exists")
        (is (= :conflict (:anomaly/type (owner/acquire! root id)))
            "a second local claim is answered by the registry")
        (finally (owner/release! held))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} owner-lock-held-by-another-process-test
  (if-not (fs/which "bb")
    (println "SKIP cross-process owner lock test: bb is not on PATH")
    (fs/with-temp-dir [root {:prefix "run-owner-process-"}]
      (let [id     (random-uuid)
            file   (owner/owner-file root id)
            _      (fs/create-dirs (fs/parent file))
            holder (bp/process ["bb" "-e" holder-script (str file)]
                               {:in :pipe :out :pipe :err :inherit})
            locked (future (.readLine ^java.io.BufferedReader (io/reader (:out holder))))]
        (try
          (is (= "locked" (deref locked 10000 ::timed-out)))
          (testing "while the other process holds it, the run is refused"
            (is (= :conflict (:anomaly/type (owner/acquire! root id))))
            (is (some? (owner/owner root id))))
          (finally
            (doto ^java.io.Writer (io/writer (:in holder)) (.write "go\n") (.flush))
            (deref holder 10000 nil)))
        (testing "once it exits, the OS has released the lock"
          (let [held (owner/acquire! root id)]
            (is (not (anomaly/anomaly? held)))
            (owner/release! held)))))))

(deftest ^{:stratum 1} local-claims-and-probes-keep-the-os-lock-test
  (if-not (fs/which "bb")
    (println "SKIP cross-process owner lock test: bb is not on PATH")
    (fs/with-temp-dir [root {:prefix "run-owner-local-"}]
      (let [id   (random-uuid)
            held (owner/acquire! root id)
            seen (fn [] (clojure.string/trim
                         (:out (bp/shell {:out :string :err :inherit}
                                         "bb" "-e" probe-script (str (owner/owner-file root id))))))]
        (try
          (is (= :conflict (:anomaly/type (owner/acquire! root id))))
          (is (some? (owner/owner root id)))
          (testing "after a rejected claim and a probe here, another process still finds it held"
            (is (= "busy" (seen))))
          (finally (owner/release! held)))
        (is (= "free" (seen)))))))
