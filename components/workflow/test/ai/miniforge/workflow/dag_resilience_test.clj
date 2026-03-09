(ns ai.miniforge.workflow.dag-resilience-test
  "Tests for DAG resilience: quota detection, event emission, and resume.

   Layer 0: quota-error? — pattern matching against error messages
   Layer 0: analyze-batch-for-quota — batch categorization
   Layer 1: emit-* — checkpoint event emission (nil-safety)
   Layer 2: safe-read-edn — tolerant EDN parsing
   Layer 2: read-event-file — file I/O with graceful degradation
   Layer 2: extract-* — pure projection from event sequences
   Layer 2: resume-context-from-event-file — end-to-end file→context"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.workflow.dag-resilience :as resilience]))

;------------------------------------------------------------------------------ Helpers

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

;------------------------------------------------------------------------------ Layer 0: Quota detection

(deftest quota-error-detects-known-patterns-test
  (testing "detects 'You've hit your limit' pattern"
    (is (resilience/quota-error?
         (dag/err :task-execution-failed "You've hit your limit · resets 3pm" {}))))

  (testing "detects rate limit pattern"
    (is (resilience/quota-error?
         (dag/err :task-execution-failed "API rate limit exceeded" {}))))

  (testing "detects 429 status"
    (is (resilience/quota-error?
         (dag/err :task-execution-failed "429 Too Many Requests" {}))))

  (testing "detects quota exceeded pattern"
    (is (resilience/quota-error?
         (dag/err :task-execution-failed "Your quota exceeded for this billing period" {}))))

  (testing "detects 'resets Xpm' timestamp pattern"
    (is (resilience/quota-error?
         (dag/err :task-execution-failed "Rate limited. resets 3pm (America/Los_Angeles)" {})))))

(deftest quota-error-negative-cases-test
  (testing "does not match normal errors"
    (is (not (resilience/quota-error?
              (dag/err :task-execution-failed "NullPointerException at line 42" {})))))

  (testing "ok results are not quota errors"
    (is (not (resilience/quota-error? (dag/ok {:status :done})))))

  (testing "nil result is not a quota error"
    (is (not (resilience/quota-error? nil)))))

(deftest quota-error-nested-message-paths-test
  (testing "finds message at :error :data :message path"
    (let [result {:status :err
                  :error {:type :task-execution-failed
                          :data {:message "You've hit your limit · resets 5pm"}}}]
      (is (resilience/quota-error? result))))

  (testing "stringifies non-string error for pattern matching"
    (let [result {:status :err
                  :error {:message {:nested "429 Too Many Requests"}}}]
      (is (resilience/quota-error? result)))))

;------------------------------------------------------------------------------ Layer 0: Batch analysis

(deftest analyze-batch-for-quota-categorization-test
  (testing "categorizes mixed batch correctly"
    (let [results {:task-a (dag/ok {:status :done})
                   :task-b (dag/err :failed "You've hit your limit" {})
                   :task-c (dag/err :failed "NPE" {})}
          analysis (resilience/analyze-batch-for-quota results)]
      (is (= #{:task-a} (:completed-ids analysis)))
      (is (= #{:task-b} (:quota-limited-ids analysis)))
      (is (= #{:task-c} (:other-failed-ids analysis)))))

  (testing "all-ok batch"
    (let [analysis (resilience/analyze-batch-for-quota
                    {:a (dag/ok {}) :b (dag/ok {})})]
      (is (= #{:a :b} (:completed-ids analysis)))
      (is (empty? (:quota-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis)))))

  (testing "all-quota batch"
    (let [analysis (resilience/analyze-batch-for-quota
                    {:a (dag/err :f "rate limit" {})
                     :b (dag/err :f "You've hit your limit" {})})]
      (is (empty? (:completed-ids analysis)))
      (is (= #{:a :b} (:quota-limited-ids analysis)))))

  (testing "empty batch"
    (let [analysis (resilience/analyze-batch-for-quota {})]
      (is (empty? (:completed-ids analysis)))
      (is (empty? (:quota-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis))))))

;------------------------------------------------------------------------------ Layer 1: Emit functions — nil safety

(deftest emit-functions-tolerate-nil-event-stream-test
  (testing "emit-dag-task-completed! with nil stream does not throw"
    (is (nil? (resilience/emit-dag-task-completed! nil "wf-1" :task-a (dag/ok {})))))

  (testing "emit-dag-task-failed! with nil stream does not throw"
    (is (nil? (resilience/emit-dag-task-failed! nil "wf-1" :task-a (dag/err :f "err" {})))))

  (testing "emit-dag-paused! with nil stream does not throw"
    (is (nil? (resilience/emit-dag-paused! nil "wf-1" #{:a :b} "quota hit")))))

;------------------------------------------------------------------------------ Layer 2: safe-read-edn

(deftest safe-read-edn-normal-values-test
  (testing "reads map"
    (is (= {:a 1 :b "hello"} (resilience/safe-read-edn "{:a 1 :b \"hello\"}"))))

  (testing "reads vector"
    (is (= [1 2 3] (resilience/safe-read-edn "[1 2 3]"))))

  (testing "reads keyword"
    (is (= :foo (resilience/safe-read-edn ":foo")))))

(deftest safe-read-edn-tagged-literals-test
  (testing "reads #uuid"
    (let [result (resilience/safe-read-edn
                  "{:id #uuid \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}")]
      (is (uuid? (:id result)))
      (is (= "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" (str (:id result))))))

  (testing "reads #inst"
    (let [result (resilience/safe-read-edn
                  "{:ts #inst \"2026-03-08T18:14:28Z\"}")]
      (is (some? (:ts result)))))

  (testing "tolerates #object tags"
    (let [result (resilience/safe-read-edn
                  (str "{:err #object[java.time.Instant 0x25d0 "
                       "\"2026-03-08T18:14:28Z\"]}"))]
      (is (some? result))
      (is (string? (:err result))))))

(deftest safe-read-edn-error-cases-test
  (testing "returns nil for malformed EDN"
    (is (nil? (resilience/safe-read-edn "{:broken {{{ invalid"))))

  (testing "returns nil for empty string"
    (is (nil? (resilience/safe-read-edn ""))))

  (testing "returns nil for nil input"
    (is (nil? (resilience/safe-read-edn nil)))))

;------------------------------------------------------------------------------ Layer 2: read-event-file

(deftest read-event-file-happy-path-test
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

(deftest read-event-file-skips-blank-lines-test
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

(deftest read-event-file-skips-unparseable-lines-test
  (testing "unparseable lines are silently dropped"
    (let [dir (temp-dir)]
      (try
        (let [f (io/file dir "mixed.edn")]
          (spit f (str (pr-str {:event/type :good}) "\n"
                       "{:broken {{{ invalid\n"
                       (pr-str {:event/type :also-good}) "\n"))
          (let [result (resilience/read-event-file (.getAbsolutePath f))]
            (is (= 2 (count result)))
            (is (= :good (:event/type (first result))))
            (is (= :also-good (:event/type (second result))))))
        (finally (cleanup! dir))))))

(deftest read-event-file-missing-file-test
  (testing "returns nil for non-existent file"
    (is (nil? (resilience/read-event-file "/nonexistent/path.edn")))))

;------------------------------------------------------------------------------ Layer 2: Extract functions — pure projections

(deftest extract-completed-task-ids-test
  (testing "extracts only :dag/task-completed event task IDs"
    (let [events [{:event/type :workflow/started}
                  {:event/type :dag/task-completed :dag/task-id :task-a}
                  {:event/type :dag/task-failed :dag/task-id :task-b}
                  {:event/type :dag/task-completed :dag/task-id :task-c}
                  {:event/type :workflow/failed}]]
      (is (= #{:task-a :task-c} (resilience/extract-completed-task-ids events)))))

  (testing "returns empty set when no completions"
    (is (= #{} (resilience/extract-completed-task-ids
                [{:event/type :workflow/started}
                 {:event/type :workflow/failed}]))))

  (testing "returns empty set for empty events"
    (is (= #{} (resilience/extract-completed-task-ids []))))

  (testing "deduplicates if same task appears twice"
    (let [events [{:event/type :dag/task-completed :dag/task-id :task-a}
                  {:event/type :dag/task-completed :dag/task-id :task-a}]]
      (is (= #{:task-a} (resilience/extract-completed-task-ids events))))))

(deftest extract-completed-artifacts-test
  (testing "collects artifacts from completed tasks"
    (let [events [{:event/type :dag/task-completed
                   :dag/task-id :task-a
                   :dag/result {:data {:artifacts [{:code/id "art-1"}]}}}
                  {:event/type :dag/task-completed
                   :dag/task-id :task-b
                   :dag/result {:data {:artifacts [{:code/id "art-2"} {:code/id "art-3"}]}}}]]
      (is (= 3 (count (resilience/extract-completed-artifacts events))))
      (is (= "art-1" (:code/id (first (resilience/extract-completed-artifacts events)))))))

  (testing "handles tasks with no :artifacts key"
    (let [events [{:event/type :dag/task-completed
                   :dag/task-id :task-a
                   :dag/result {:data {}}}]]
      (is (= [] (resilience/extract-completed-artifacts events)))))

  (testing "ignores non-completed events"
    (let [events [{:event/type :dag/task-failed
                   :dag/task-id :task-a
                   :dag/result {:data {:artifacts [{:code/id "should-ignore"}]}}}]]
      (is (= [] (resilience/extract-completed-artifacts events))))))

;------------------------------------------------------------------------------ Layer 2: Resume context — end-to-end

(deftest resume-context-end-to-end-test
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
                       :dag/result {:data {:artifacts [{:code/id "art-2"}]}}}
                      {:event/type :workflow/failed}]
              f (write-events! dir "wf-123.edn" events)
              ;; Exercise the composable pipeline manually
              ;; (resume-context-from-event-file uses user.home)
              parsed (resilience/read-event-file (.getAbsolutePath f))
              completed (resilience/extract-completed-task-ids parsed)
              artifacts (resilience/extract-completed-artifacts parsed)]
          ;; Completed tasks
          (is (= #{:task-a :task-c} completed))
          ;; Failed tasks not included
          (is (not (contains? completed :task-b)))
          ;; Artifacts recovered
          (is (= 2 (count artifacts)))
          (is (= #{"art-1" "art-2"} (set (map :code/id artifacts)))))
        (finally (cleanup! dir)))))

  (testing "resume-context-from-event-file returns non-resumed for missing file"
    (let [ctx (resilience/resume-context-from-event-file "nonexistent-workflow-id")]
      (is (= #{} (:pre-completed-ids ctx)))
      (is (= [] (:pre-completed-artifacts ctx)))
      (is (false? (:resumed? ctx))))))

;------------------------------------------------------------------------------ Composability: functions compose into pipeline

(deftest composability-pipeline-test
  (testing "read → extract-ids → extract-artifacts forms a composable pipeline"
    (let [dir (temp-dir)]
      (try
        (let [events [{:event/type :dag/task-completed :dag/task-id :x
                       :dag/result {:data {:artifacts [{:id 1}]}}}
                      {:event/type :dag/task-completed :dag/task-id :y
                       :dag/result {:data {:artifacts [{:id 2}]}}}
                      {:event/type :dag/task-failed :dag/task-id :z}]
              f (write-events! dir "pipeline.edn" events)
              parsed (resilience/read-event-file (.getAbsolutePath f))
              ;; Each function is independently useful AND composable
              ids (resilience/extract-completed-task-ids parsed)
              arts (resilience/extract-completed-artifacts parsed)]
          (is (= #{:x :y} ids))
          (is (= 2 (count arts)))
          ;; The same event sequence works with both extractors
          (is (= (count ids) (count arts))))
        (finally (cleanup! dir))))))
