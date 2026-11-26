;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns graalvm-task-test
  (:require [ai.miniforge.bb-proc.interface :as proc]
            [ai.miniforge.bb-test-runner.interface :as bb-test-runner]
            [babashka.process :as process]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]
            [test-runner :as runner])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} resolver-command
  "Port identity recorded by the fixture; no resolver command is actually launched."
  "fixture-clojure")

(def ^{:stratum 0 :private true} sanitized-environment
  "Recognizable sanitizer output, not inherited Git hook variables."
  {"FIXTURE_SANITIZED" "yes"})

(def ^{:stratum 0 :private true} fixture-classpath
  "Successful resolver output preserves spaces and the platform separator."
  (str "classes with spaces" File/pathSeparator "library.jar"))

(def ^{:stratum 0 :private true} resolver-failure-exit
  "Distinguish the resolver's exact failure from a generic compatibility failure."
  23)

(defn- ^{:stratum 0} process-program [exit output error]
  (str "(print " (pr-str output) ") (flush) "
       "(binding [*out* *err*] (print " (pr-str error) ") (flush)) "
       "(System/exit " exit ")"))

(defn- ^{:stratum 0} record-resolution! [calls result options & arguments]
  (swap! calls conj [options (vec arguments)])
  result)

(defn- ^{:stratum 0} record-compatibility! [calls & arguments]
  (swap! calls conj (vec arguments))
  0)

(defn- ^{:stratum 0} capture-task! []
  (try+
    (with-out-str (runner/graalvm))
    nil
    (catch [:type :babashka.process/error] _ (:throwable &throw-context))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} completed-process! [exit output error]
  (process/sh "bb" "-e" (process-program exit output error)))

(defn- ^{:stratum 1} invoke! [result]
  (let [resolution (atom [])
        compatibility (atom [])
        resolver (partial record-resolution! resolution result)
        compatible (partial record-compatibility! compatibility)
        command (constantly resolver-command)
        sanitize (constantly sanitized-environment)
        overrides {#'process/sh resolver
                   #'runner/run-stream! compatible
                   #'proc/clojure-command command
                   #'bb-test-runner/sanitize-git-worktree-env sanitize}
        failure (with-redefs-fn overrides capture-task!)
        resolved @resolution
        tested @compatibility]
    {:failure failure
     :resolution resolved
     :compatibility tested}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-failed-resolver-preserves-the-process-error-and-never-runs-compatibility
  (let [error "dependency resolution fixture failed"
        result (completed-process! resolver-failure-exit "" error)
        observation (invoke! result)
        failure (:failure observation)]
    (is (= resolver-failure-exit (:exit (ex-data failure))))
    (is (= error (:err (ex-data failure))))
    (is (empty? (:compatibility observation)))))

(deftest ^{:stratum 2} test-successful-resolver-keeps-the-sanitized-environment-and-classpath
  (let [result (completed-process! 0 (str fixture-classpath "\n") "")
        observation (invoke! result)
        [[options arguments]] (:resolution observation)
        expected-classpath (str fixture-classpath File/pathSeparator "tests")]
    (is (nil? (:failure observation)))
    (is (= sanitized-environment (:env options)))
    (is (= [resolver-command "-A:dev" "-Spath"] arguments))
    (is (= 1 (count (:compatibility observation))))
    (is (= ["bb" "-cp" expected-classpath]
           (take 3 (first (:compatibility observation)))))))

(comment (clojure.test/run-tests 'graalvm-task-test))
