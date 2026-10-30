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

(def ^{:stratum 0} ^:private process-timeout-ms
  "How long a test waits for the second bb process."
  10000)

(def ^{:stratum 0} ^:private bb-required
  "The cross-process tests run a second process with bb."
  "bb must be on PATH for the cross-process owner lock tests")

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
      (is (= :done (owner/with-run-owner root id (constantly :done)))))))

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
    (testing "the run pins its root's physical path for the lock, the context and a restored snapshot"
      (let [real (fs/create-dirs (fs/path root "real-root"))
            alias (fs/create-sym-link (fs/path root "alias-root") real)
            seen (atom nil)]
        (with-redefs-fn {#'ai.miniforge.workflow.runner/acquire-environment
                         (fn [_ _ opts] (reset! seen opts) (throw (ex-info "stop here" {})))}
          #(try (runner/run-pipeline {:workflow/id :owner-test :workflow/pipeline []} {}
                                     {:checkpoint/root (str alias)
                                      :execution/checkpoint-root (str alias)
                                      :resume-machine-snapshot {:execution/id (random-uuid)
                                                                :execution/checkpoint-root (str alias)}
                                      :resume-phase-results {}})
                (catch clojure.lang.ExceptionInfo _ nil)))
        (is (= (str (fs/real-path real)) (:checkpoint/root @seen)))
        (is (= (str (fs/real-path real)) (:execution/checkpoint-root @seen)))
        (is (= (str (fs/real-path real))
               (get-in @seen [:resume-machine-snapshot :execution/checkpoint-root])))))
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

(deftest ^{:stratum 0} a-root-the-lock-creates-is-private-test
  ;; Made as checkpoint directories are, whatever the umask, so the
  ;; checkpoint writes' directory check accepts it.
  (fs/with-temp-dir [dir {:prefix "run-owner-private-"}]
    (let [root (fs/path dir "checkpoints")
          held (owner/acquire! root (random-uuid))]
      (try
        (is (not (anomaly/anomaly? held)))
        (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions root))))
        (finally (owner/release! held))))))

(deftest ^{:stratum 0} a-claim-tries-again-past-a-probe-test
  ;; Another process's probe holds the lock for an instant; a claim that
  ;; meets it tries once more before refusing the run.
  (fs/with-temp-dir [root {:prefix "run-owner-retry-"}]
    (let [try-lock @#'owner/try-lock
          calls    (atom 0)
          held     (with-redefs-fn {#'owner/try-lock
                                    (fn [channel] (when (< 1 (swap! calls inc)) (try-lock channel)))}
                     #(owner/acquire! root (random-uuid)))]
      (try
        (is (not (anomaly/anomaly? held)))
        (is (= 2 @calls))
        (finally (when-not (anomaly/anomaly? held) (owner/release! held)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} other-process-sees
  "What a separate bb process finds when it tries `file`'s lock."
  [file]
  (clojure.string/trim (:out (bp/shell {:out :string :err :inherit} "bb" "-e" probe-script (str file)))))

(deftest ^{:stratum 1} owner-lock-held-by-another-process-test
  (when (is (fs/which "bb") bb-required)
    (fs/with-temp-dir [root {:prefix "run-owner-process-"}]
      (let [id     (random-uuid)
            file   (owner/owner-file root id)
            _      (fs/create-dirs (fs/parent file))
            holder (bp/process ["bb" "-e" holder-script (str file)]
                               {:in :pipe :out :pipe :err :inherit})
            locked (future (.readLine ^java.io.BufferedReader (io/reader (:out holder))))]
        (try
          (is (= "locked" (deref locked process-timeout-ms ::timed-out)))
          (testing "while the other process holds it, the run is refused"
            (is (= :conflict (:anomaly/type (owner/acquire! root id))))
            (is (some? (owner/owner root id))))
          (finally
            (doto ^java.io.Writer (io/writer (:in holder)) (.write "go\n") (.flush))
            (deref holder process-timeout-ms nil)))
        (testing "once it exits, the OS has released the lock"
          (let [held (owner/acquire! root id)]
            (is (not (anomaly/anomaly? held)))
            (owner/release! held)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} local-claims-and-probes-keep-the-os-lock-test
  (when (is (fs/which "bb") bb-required)
    (fs/with-temp-dir [root {:prefix "run-owner-local-"}]
      (let [id   (random-uuid)
            held (owner/acquire! root id)
            seen #(other-process-sees (owner/owner-file root id))]
        (try
          (is (= :conflict (:anomaly/type (owner/acquire! root id))))
          (is (some? (owner/owner root id)))
          (testing "after a rejected claim and a probe here, another process still finds it held"
            (is (= "busy" (seen))))
          (finally (owner/release! held)))
        (is (= "free" (seen)))))))

(deftest ^{:stratum 2} probes-through-another-spelling-keep-the-os-lock-test
  (when (is (fs/which "bb") bb-required)
    (doseq [[case-name link-target claim probe]
            [["an unborn segment's .. exposing a link"
              "real" ["unused" ".." "alias" "checkpoints"] ["real" "checkpoints"]]
             ["a .. after a link resolves through the link's target"
              "real/child" ["unused" ".." "alias" ".." "checkpoints"] ["real" "checkpoints"]]]]
      (fs/with-temp-dir [dir {:prefix "run-owner-spelling-"}]
        (let [_     (fs/create-dirs (fs/path dir link-target))
              _     (fs/create-sym-link (fs/path dir "alias") (fs/path dir link-target))
              claim (str (apply fs/path dir claim))
              probe (str (apply fs/path dir probe))
              id    (random-uuid)
              held  (owner/acquire! claim id)
              seen  #(other-process-sees (owner/owner-file probe id))]
          (testing case-name
            (try
              (is (not (anomaly/anomaly? held)))
              (is (some? (owner/owner probe id)) "the probe is answered by the registry")
              (is (= "busy" (seen)) "and another process still finds the lock held")
              (finally (when-not (anomaly/anomaly? held) (owner/release! held))))))))))

(deftest ^{:stratum 2} a-probe-through-a-missing-segment-keeps-the-os-lock-test
  ;; `unused/..` cannot be resolved by the OS, so the spelling names nothing;
  ;; normalized, it would open (and on close release) the held file.
  (when (is (fs/which "bb") bb-required)
    (fs/with-temp-dir [dir {:prefix "run-owner-missing-"}]
      (let [real  (fs/create-dirs (fs/path dir "real" "checkpoints"))
            _     (fs/create-sym-link (fs/path dir "alias") (fs/path dir "real"))
            id    (random-uuid)
            held  (owner/acquire! real id)]
        (try
          (is (nil? (owner/owner (str (fs/path dir "unused" ".." "alias" "checkpoints")) id)))
          (is (= "busy" (other-process-sees (owner/owner-file real id))))
          (finally (owner/release! held)))))))
