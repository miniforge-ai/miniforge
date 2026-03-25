(ns ai.miniforge.workflow.dag-resilience-resume-test
  "Tests for resume helpers: safe-read-edn, read-event-file, extract functions, and resume context."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.workflow.dag-resilience :as resilience]))

;------------------------------------------------------------------------------ Resume helpers

(defn temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-resilience-test-" (System/nanoTime)))
    .mkdirs))

(defn cleanup! [dir]
  (doseq [f (.listFiles dir)]
    (when (.isDirectory f) (cleanup! f))
    (.delete f))
  (.delete dir))

(defn write-events! [dir filename events]
  (let [f (io/file dir filename)]
    (with-open [w (io/writer f)]
      (doseq [event events]
        (.write w (pr-str event))
        (.write w "\n")))
    f))

;------------------------------------------------------------------------------ Layer 3: safe-read-edn

(deftest test-safe-read-edn-normal-values
  (testing "reads map"
    (is (= {:a 1 :b "hello"} (resilience/safe-read-edn "{:a 1 :b \"hello\"}"))))
  (testing "reads vector"
    (is (= [1 2 3] (resilience/safe-read-edn "[1 2 3]"))))
  (testing "reads keyword"
    (is (= :foo (resilience/safe-read-edn ":foo")))))

(deftest test-safe-read-edn-tagged-literals
  (testing "reads #uuid"
    (let [result (resilience/safe-read-edn
                  "{:id #uuid \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}")]
      (is (uuid? (:id result)))))
  (testing "reads #inst"
    (let [result (resilience/safe-read-edn
                  "{:ts #inst \"2026-03-08T18:14:28Z\"}")]
      (is (some? (:ts result)))))
  (testing "tolerates #object tags"
    (let [result (resilience/safe-read-edn
                  (str "{:err #object[java.time.Instant 0x25d0 "
                       "\"2026-03-08T18:14:28Z\"]}"))]
      (is (some? result)))))

(deftest test-safe-read-edn-error-cases
  (testing "returns nil for malformed EDN"
    (is (nil? (resilience/safe-read-edn "{:broken {{{ invalid"))))
  (testing "returns nil for empty string"
    (is (nil? (resilience/safe-read-edn ""))))
  (testing "returns nil for nil input"
    (is (nil? (resilience/safe-read-edn nil)))))

;------------------------------------------------------------------------------ Layer 3: read-event-file

(deftest test-read-event-file-happy-path
  (testing "reads all events from file"
    (let [dir (temp-dir)]
      (try
        (let [events [{:event/type :workflow/started :workflow/id "test"}
                      {:event/type :dag/task-completed :dag/task-id :task-a}
                      {:event/type :workflow/failed}]
              f (write-events! dir "test.edn" events)
              result (resilience/read-event-file (.getAbsolutePath f))]
          (is (= 3 (count result)))
          (is (= :workflow/started (:event/type (first result))))
          (is (= :workflow/failed (:event/type (last result)))))
        (finally (cleanup! dir))))))

(deftest test-read-event-file-skips-blank-lines
  (testing "blank lines between events are ignored"
    (let [dir (temp-dir)]
      (try
        (let [f (io/file dir "gaps.edn")]
          (spit f (str (pr-str {:event/type :a}) "\n"
                       "\n"
                       "   \n"
                       (pr-str {:event/type :b}) "\n"))
          (let [result (resilience/read-event-file (.getAbsolutePath f))]
            (is (= 2 (count result)))))
        (finally (cleanup! dir))))))

(deftest test-read-event-file-missing-file
  (testing "returns nil for non-existent file"
    (is (nil? (resilience/read-event-file "/nonexistent/path.edn")))))

;------------------------------------------------------------------------------ Layer 3: Extract functions

(deftest test-extract-completed-task-ids
  (testing "extracts only :dag/task-completed event task IDs"
    (let [events [{:event/type :workflow/started}
                  {:event/type :dag/task-completed :dag/task-id :task-a}
                  {:event/type :dag/task-failed :dag/task-id :task-b}
                  {:event/type :dag/task-completed :dag/task-id :task-c}
                  {:event/type :workflow/failed}]]
      (is (= #{:task-a :task-c} (resilience/extract-completed-task-ids events)))))
  (testing "returns empty set when no completions"
    (is (= #{} (resilience/extract-completed-task-ids
                [{:event/type :workflow/started}]))))
  (testing "deduplicates"
    (let [events [{:event/type :dag/task-completed :dag/task-id :task-a}
                  {:event/type :dag/task-completed :dag/task-id :task-a}]]
      (is (= #{:task-a} (resilience/extract-completed-task-ids events))))))

(deftest test-extract-completed-artifacts
  (testing "collects artifacts from completed tasks"
    (let [events [{:event/type :dag/task-completed
                   :dag/task-id :task-a
                   :dag/result {:data {:artifacts [{:code/id "art-1"}]}}}
                  {:event/type :dag/task-completed
                   :dag/task-id :task-b
                   :dag/result {:data {:artifacts [{:code/id "art-2"} {:code/id "art-3"}]}}}]]
      (is (= 3 (count (resilience/extract-completed-artifacts events))))))
  (testing "handles tasks with no artifacts"
    (let [events [{:event/type :dag/task-completed
                   :dag/result {:data {}}}]]
      (is (= [] (resilience/extract-completed-artifacts events)))))
  (testing "ignores non-completed events"
    (let [events [{:event/type :dag/task-failed
                   :dag/result {:data {:artifacts [{:code/id "ignored"}]}}}]]
      (is (= [] (resilience/extract-completed-artifacts events))))))

;------------------------------------------------------------------------------ Layer 3: Resume context end-to-end

(deftest test-resume-context-end-to-end
  (testing "builds full resume context from event file"
    (let [dir (temp-dir)]
      (try
        (let [events [{:event/type :workflow/started :workflow/id "wf-123"}
                      {:event/type :dag/task-completed
                       :dag/task-id :task-a
                       :dag/result {:data {:artifacts [{:code/id "art-1"}]}}}
                      {:event/type :dag/task-failed
                       :dag/task-id :task-b
                       :dag/error {:message "quota"}}
                      {:event/type :dag/task-completed
                       :dag/task-id :task-c
                       :dag/result {:data {:artifacts [{:code/id "art-2"}]}}}]
              f (write-events! dir "wf-123.edn" events)
              parsed (resilience/read-event-file (.getAbsolutePath f))
              completed (resilience/extract-completed-task-ids parsed)
              artifacts (resilience/extract-completed-artifacts parsed)]
          (is (= #{:task-a :task-c} completed))
          (is (not (contains? completed :task-b)))
          (is (= 2 (count artifacts))))
        (finally (cleanup! dir)))))
  (testing "resume-context-from-event-file returns non-resumed for missing file"
    (let [ctx (resilience/resume-context-from-event-file "nonexistent-workflow-id")]
      (is (= #{} (:pre-completed-ids ctx)))
      (is (= [] (:pre-completed-artifacts ctx)))
      (is (false? (:resumed? ctx))))))
