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

(ns ai.miniforge.cli.workflow-runner.manifest-wiring-test
  "Unit tests for the BD-2b sub-3a manifest wiring helpers in
   `workflow_runner.clj`. The manifest module itself
   (`event-stream.manifest`) is jvm-only with its own comprehensive
   suite from sub-2; these tests focus on the runner-side
   orchestration: lifecycle ordering, idempotent terminal marking,
   nil-event-stream short-circuit, exception-tolerant cleanup."
  (:require
   [clojure.test :refer [deftest is]]
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Fixture

(defn- mock-manifest-fns
  "Build a map of mock manifest fns recording every call into `calls`.
   `start-heartbeat!` returns a sentinel handle that `stop-heartbeat!`
   recognises."
  [calls]
  (let [heartbeat-handle :mock-heartbeat-handle]
    {'init-active      (fn [wid] (swap! calls conj [:init-active wid])
                          {:workflow_id wid :status :active})
     'save-manifest!   (fn [dir m] (swap! calls conj [:save dir m]))
     'load-manifest    (fn [dir]
                         (swap! calls conj [:load dir])
                         {:workflow_id :w :status :active})
     'mark-terminal    (fn [m status] (swap! calls conj [:mark-terminal status])
                          (assoc m :status status))
     'start-heartbeat! (fn [dir]
                         (swap! calls conj [:start-heartbeat dir])
                         heartbeat-handle)
     'stop-heartbeat!  (fn [hb] (swap! calls conj [:stop-heartbeat hb]))}))

(defn- with-mocked-manifest
  "Bind manifest interface functions. Returns whatever `f` returns;
   the call log is in `calls`."
  [f]
  (let [calls (atom [])
        mocks (mock-manifest-fns calls)]
    (with-redefs [sut/*manifest-ops* {:init-active (get mocks 'init-active)
                                      :save-manifest! (get mocks 'save-manifest!)
                                      :load-manifest (get mocks 'load-manifest)
                                      :mark-terminal (get mocks 'mark-terminal)
                                      :start-heartbeat! (get mocks 'start-heartbeat!)
                                      :stop-heartbeat! (get mocks 'stop-heartbeat!)
                                      :archive-workflow! (fn [& _])}
                  es/workflow-dir (fn [wid] (java.io.File. (str "/tmp/test-" wid)))]
      (f calls))))

;------------------------------------------------------------------------------ start-workflow-manifest!

(deftest start-workflow-manifest!-no-op-when-event-stream-is-nil
  (with-mocked-manifest
    (fn [calls]
      (is (nil? (sut/start-workflow-manifest! :wid nil)))
      (is (empty? @calls)
          "no manifest writes when there's no event stream
           (dashboard-only runs don't persist events)"))))

(deftest start-workflow-manifest!-inits-and-starts-heartbeat
  (with-mocked-manifest
    (fn [calls]
      (let [handle (sut/start-workflow-manifest! :wid :fake-es)
            ordered (mapv first @calls)]
        (is (some? handle))
        (is (some? (:dir handle)))
        (is (= :mock-heartbeat-handle (:heartbeat handle)))
        (is (false? @(:marked? handle))
            "marked? starts false; flips on first mark-manifest-terminal!")
        (is (= [:init-active :save :start-heartbeat] ordered)
            "lifecycle order: build active manifest → save → start heartbeat")))))

;------------------------------------------------------------------------------ mark-manifest-terminal!

(deftest mark-manifest-terminal!-no-op-when-handle-nil
  (with-mocked-manifest
    (fn [calls]
      (sut/mark-manifest-terminal! nil :completed)
      (is (empty? @calls)))))

(deftest mark-manifest-terminal!-loads-marks-and-saves
  (with-mocked-manifest
    (fn [calls]
      (let [handle (sut/start-workflow-manifest! :wid :fake-es)]
        (reset! calls [])
        (sut/mark-manifest-terminal! handle :completed)
        (is (= [:load :mark-terminal :save] (mapv first @calls))
            "mark-terminal must load → transition → save in order")
        (is (true? @(:marked? handle)))))))

(deftest mark-manifest-terminal!-leaves-marked?-false-when-manifest-absent
  ;; load-manifest returns nil when the file isn't on disk (e.g. the
  ;; archive flow moved it between ticks). Marking must not flip the
  ;; idempotency flag in that case — otherwise the finally's
  ;; :cancelled fallback (and any future retry) would be permanently
  ;; suppressed even though no terminal status ever got written.
  (let [calls (atom [])
        nil-load-mocks (assoc (mock-manifest-fns calls)
                              'load-manifest (fn [dir]
                                               (swap! calls conj [:load dir])
                                               nil))]
    (with-redefs [sut/*manifest-ops* {:init-active (get nil-load-mocks 'init-active)
                                      :save-manifest! (get nil-load-mocks 'save-manifest!)
                                      :load-manifest (get nil-load-mocks 'load-manifest)
                                      :mark-terminal (get nil-load-mocks 'mark-terminal)
                                      :start-heartbeat! (get nil-load-mocks 'start-heartbeat!)
                                      :stop-heartbeat! (get nil-load-mocks 'stop-heartbeat!)
                                      :archive-workflow! (fn [& _])}
                  es/workflow-dir (fn [wid] (java.io.File. (str "/tmp/test-" wid)))]
      (let [handle (sut/start-workflow-manifest! :wid :fake-es)]
        (reset! calls [])
        (sut/mark-manifest-terminal! handle :completed)
        (is (false? @(:marked? handle))
            "absent manifest → marked? stays false so subsequent
             attempts can still try to write the terminal status")
        (is (= [:load] (mapv first @calls))
            "load was attempted but no save fired (nothing to update)")))))

(deftest mark-manifest-terminal!-idempotent-via-marked?
  (with-mocked-manifest
    (fn [calls]
      (let [handle (sut/start-workflow-manifest! :wid :fake-es)]
        (reset! calls [])
        (sut/mark-manifest-terminal! handle :completed)
        ;; Second call (e.g. the finally block firing :cancelled after
        ;; the happy path already marked :completed) must be a no-op
        ;; so we don't overwrite the success status.
        (sut/mark-manifest-terminal! handle :cancelled)
        (let [statuses (filter #(= :mark-terminal (first %)) @calls)]
          (is (= 1 (count statuses))
              "second mark-manifest-terminal! must not fire after marked? is true")
          (is (= [:mark-terminal :completed] (first statuses))
              "the first status sticks"))))))

;------------------------------------------------------------------------------ finish-workflow-manifest!

(deftest finish-workflow-manifest!-no-op-when-handle-nil
  (with-mocked-manifest
    (fn [calls]
      (sut/finish-workflow-manifest! nil)
      (is (empty? @calls)))))

(deftest finish-workflow-manifest!-stops-heartbeat
  (with-mocked-manifest
    (fn [calls]
      (let [handle (sut/start-workflow-manifest! :wid :fake-es)]
        (reset! calls [])
        (sut/finish-workflow-manifest! handle)
        (is (= [[:stop-heartbeat :mock-heartbeat-handle]] @calls))))))

(deftest finish-workflow-manifest!-swallows-throwables
  ;; stop-heartbeat! is called from a finally block during shutdown.
  ;; An IO failure there must not mask the actual workflow result.
  (let [calls (atom [])
        boom-mocks (assoc (mock-manifest-fns calls)
                          'stop-heartbeat! (fn [_] (throw (RuntimeException. "boom"))))]
    (with-redefs [sut/*manifest-ops* {:init-active (get boom-mocks 'init-active)
                                      :save-manifest! (get boom-mocks 'save-manifest!)
                                      :load-manifest (get boom-mocks 'load-manifest)
                                      :mark-terminal (get boom-mocks 'mark-terminal)
                                      :start-heartbeat! (get boom-mocks 'start-heartbeat!)
                                      :stop-heartbeat! (get boom-mocks 'stop-heartbeat!)
                                      :archive-workflow! (fn [& _])}
                  es/workflow-dir (fn [_] (java.io.File. "/tmp/test"))]
      (let [handle (sut/start-workflow-manifest! :wid :fake-es)]
        (is (nil? (sut/finish-workflow-manifest! handle))
            "stop-heartbeat! throw must not propagate")))))

;------------------------------------------------------------------------------ archive-workflow-manifest!

(deftest archive-workflow-manifest!-no-op-when-marked?-false
  ;; If mark-manifest-terminal! never wrote (manifest absent or no
  ;; event stream), there's nothing to archive — the archive op
  ;; would error on the missing manifest. Skip in that case.
  (let [archive-calls (atom [])]
    (with-redefs [sut/*manifest-ops* {:archive-workflow! (fn [& args]
                                                           (swap! archive-calls conj args))}]
      (let [handle {:dir (java.io.File. "/tmp/x") :marked? (atom false)}]
        (sut/archive-workflow-manifest! handle :wid)
        (is (empty? @archive-calls)
            "no archive call when marked? is false")))))

(deftest archive-workflow-manifest!-no-op-when-dir-nil
  ;; nil dir means dashboard-only run; no manifest to archive.
  (let [archive-calls (atom [])]
    (with-redefs [sut/*manifest-ops* {:archive-workflow! (fn [& args]
                                                           (swap! archive-calls conj args))}]
      (sut/archive-workflow-manifest! {:dir nil :marked? (atom true)} :wid)
      (is (empty? @archive-calls)))))

(deftest archive-workflow-manifest!-invokes-archive-workflow!
  (let [archive-calls (atom [])]
    (with-redefs [sut/*manifest-ops* {:archive-workflow! (fn [& args]
                                                           (swap! archive-calls conj args))}]
      (sut/archive-workflow-manifest!
       {:dir (java.io.File. "/tmp/x") :marked? (atom true)}
       :wid)
      (is (= [[:wid]] @archive-calls)
          "archive-workflow! called once with the workflow id"))))

(deftest archive-workflow-manifest!-swallows-archive-failures
  ;; Per the docstring, archive failures must not propagate — the
  ;; boot-time recovery pass picks up half-finished archives. A
  ;; failing archive should log to stderr and return nil.
  (with-redefs [sut/*manifest-ops* {:archive-workflow! (fn [& _]
                                                         (throw (RuntimeException. "rename failed")))}]
    (binding [*err* (java.io.PrintWriter. (java.io.StringWriter.))]
      (is (nil? (sut/archive-workflow-manifest!
                 {:dir (java.io.File. "/tmp/x") :marked? (atom true)}
                 :wid))
          "archive exception must not propagate"))))

(deftest finish-workflow-manifest!-restores-interrupt-flag
  ;; stop-heartbeat! raises InterruptedException via awaitTermination.
  ;; Swallowing it without restoring the thread's interrupt flag
  ;; breaks cooperative cancellation — outer frames lose the signal
  ;; that they're being asked to shut down. We re-interrupt and
  ;; still return nil so cleanup stays best-effort.
  (let [calls (atom [])
        interrupt-mocks (assoc (mock-manifest-fns calls)
                               'stop-heartbeat! (fn [_]
                                                  (throw (InterruptedException. "shutdown"))))]
    ;; Clear any leaked interrupt before the test.
    (Thread/interrupted)
    (with-redefs [sut/*manifest-ops* {:init-active (get interrupt-mocks 'init-active)
                                      :save-manifest! (get interrupt-mocks 'save-manifest!)
                                      :load-manifest (get interrupt-mocks 'load-manifest)
                                      :mark-terminal (get interrupt-mocks 'mark-terminal)
                                      :start-heartbeat! (get interrupt-mocks 'start-heartbeat!)
                                      :stop-heartbeat! (get interrupt-mocks 'stop-heartbeat!)
                                      :archive-workflow! (fn [& _])}
                  es/workflow-dir (fn [_] (java.io.File. "/tmp/test"))]
      (let [handle (sut/start-workflow-manifest! :wid :fake-es)
            result (sut/finish-workflow-manifest! handle)
            interrupted? (Thread/interrupted)]
        (is (nil? result)
            "InterruptedException is still swallowed (best-effort cleanup)")
        (is (true? interrupted?)
            "current thread's interrupt flag must be restored
             so cooperative-cancellation callers above us see it")))))
