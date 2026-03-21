(ns ai.miniforge.tui-views.persistence-test
  "Tests for workflow detail loading and EDN resilience.

   Reproduces the 'no data' bug where workflow detail views show empty
   when events contain non-standard EDN tags like #object[...]."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.update.events :as events]))

;; -------------------------------------------------------------------------- Helpers

(defn temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-persistence-test-" (System/nanoTime)))
    .mkdirs))

(defn cleanup! [dir]
  (doseq [f (when (.isDirectory dir) (.listFiles dir))]
    (.delete f))
  (.delete dir))

(defn write-event-lines!
  "Write raw EDN strings (one per line) into an event file."
  [dir workflow-id lines]
  (let [file (io/file dir (str workflow-id ".edn"))]
    (spit file (clojure.string/join "\n" lines))
    file))

(defn write-events!
  "Write event maps (via pr-str) into an event file."
  [dir workflow-id events]
  (let [file (io/file dir (str workflow-id ".edn"))]
    (with-open [w (io/writer file)]
      (doseq [event events]
        (.write w (pr-str event))
        (.write w "\n")))
    file))

;; -------------------------------------------------------------------------- Sample data

(def sample-wf-id (java.util.UUID/fromString "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))

(def sample-spec {:name "Decompose Large Test Files" :version "2.0.0"})

(def sample-started-event
  {:event/type :workflow/started
   :event/id (random-uuid)
   :event/timestamp #inst "2026-03-08T18:15:46.570-00:00"
   :workflow/id sample-wf-id
   :workflow/spec sample-spec})

(def sample-phase-started-event
  {:event/type :workflow/phase-started
   :event/id (random-uuid)
   :event/timestamp #inst "2026-03-08T18:16:00.000-00:00"
   :workflow/id sample-wf-id
   :workflow/phase :analyze})

(def sample-agent-status-event
  {:event/type :agent/status
   :event/id (random-uuid)
   :workflow/id sample-wf-id
   :agent/id :codebase-analyzer
   :status/type :running
   :message "Analyzing test file structure"})

(def sample-phase-completed-event
  {:event/type :workflow/phase-completed
   :event/id (random-uuid)
   :event/timestamp #inst "2026-03-08T18:20:00.000-00:00"
   :workflow/id sample-wf-id
   :workflow/phase :analyze
   :phase/outcome :success
   :phase/duration-ms 240000
   :phase/artifacts [{:type :plan :plan/name "decomposition-plan"}]
   :phase/tokens 5000
   :phase/cost-usd 0.05})

(def sample-completed-event
  {:event/type :workflow/completed
   :event/id (random-uuid)
   :event/timestamp #inst "2026-03-08T18:30:00.000-00:00"
   :workflow/id sample-wf-id
   :workflow/status :success
   :workflow/duration-ms 900000})

;; Raw EDN line with #object tag — reproduces real miniforge event-stream output
(def failed-event-with-object-tags
  (str "{:event/type :workflow/failed, "
       ":workflow/id #uuid \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\", "
       ":workflow/failure-reason \"NullPointerException in verify phase\", "
       ":workflow/error-details {:message \"NPE\", "
       ":anomaly {:anomaly/timestamp #object[java.time.Instant 0x25d0a742 \"2026-03-08T18:14:28.466693Z\"]}}}"))

;; A failed event using java.util.Date (the fix) — serializes cleanly as #inst
(def clean-failed-event
  {:event/type :workflow/failed
   :workflow/id sample-wf-id
   :workflow/failure-reason "NullPointerException in verify phase"
   :workflow/error-details {:message "NPE"
                            :anomaly {:anomaly/timestamp (java.util.Date.)}}})

;; -------------------------------------------------------------------------- Tests

(deftest safe-read-edn-handles-standard-tags
  (testing "parses #uuid and #inst correctly"
    (let [result (persistence/safe-read-edn
                  (pr-str {:event/type :workflow/started
                           :workflow/id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
                           :event/timestamp #inst "2026-03-08T18:15:46.570-00:00"}))]
      (is (= :workflow/started (:event/type result)))
      (is (uuid? (:workflow/id result))))))

(deftest safe-read-edn-handles-object-tags
  (testing "#object tags are parsed via tolerant reader instead of failing"
    (let [result (persistence/safe-read-edn failed-event-with-object-tags)]
      (is (some? result) "#object should parse now")
      (is (= :workflow/failed (:event/type result)))
      (is (= sample-wf-id (:workflow/id result)))
      (is (= "NullPointerException in verify phase"
             (:workflow/failure-reason result))))))

(deftest read-events-parses-object-tags
  (testing "both standard and #object-containing lines are parsed"
    (let [dir (temp-dir)]
      (try
        (write-event-lines! dir sample-wf-id
                            [(pr-str sample-started-event)
                             failed-event-with-object-tags])
        (let [events (persistence/read-events
                      (io/file dir (str sample-wf-id ".edn")))]
          (is (= 2 (count events)) "both events should parse now")
          (is (= :workflow/started (:event/type (first events))))
          (is (= :workflow/failed (:event/type (second events)))))
        (finally (cleanup! dir))))))

(deftest detail-from-full-lifecycle
  (testing "detail-from-events builds phases, agents, artifacts from events"
    (let [events [sample-started-event
                  sample-phase-started-event
                  sample-agent-status-event
                  sample-phase-completed-event
                  sample-completed-event]
          detail (persistence/detail-from-events sample-wf-id events)]
      (is (= sample-wf-id (:workflow-id detail)))
      (is (= 1 (count (:phases detail))) "should have 1 phase")
      (is (= :analyze (:phase (first (:phases detail)))))
      (is (= :success (:status (first (:phases detail)))))
      (is (= 240000 (:duration-ms (first (:phases detail)))))
      (is (= 1 (count (:artifacts detail))) "should have 1 artifact")
      (is (= :plan (:type (first (:artifacts detail)))))
      (is (pos? (:tokens detail)))
      (is (some? (get-in detail [:evidence :intent :description]))))))

(deftest detail-from-started-only
  (testing "started-only workflow has intent but empty phases"
    (let [detail (persistence/detail-from-events sample-wf-id [sample-started-event])]
      (is (= sample-wf-id (:workflow-id detail)))
      (is (empty? (:phases detail)))
      (is (= "Decompose Large Test Files"
             (get-in detail [:evidence :intent :description]))))))

(deftest detail-from-failed-with-object-tags
  (testing "#object tags in failed event are now parsed — error is preserved"
    (let [dir (temp-dir)]
      (try
        (write-event-lines! dir sample-wf-id
                            [(pr-str sample-started-event)
                             failed-event-with-object-tags])
        (let [detail (persistence/load-workflow-detail sample-wf-id {:dir dir})]
          (is (some? detail) "should return non-nil detail")
          (is (some? (:error detail)) "error should be present now")
          (is (empty? (:phases detail)) "still no phase data"))
        (finally (cleanup! dir))))))

(deftest load-workflow-detail-full-lifecycle
  (testing "load-workflow-detail from disk returns populated detail"
    (let [dir (temp-dir)]
      (try
        (write-events! dir sample-wf-id
                       [sample-started-event
                        sample-phase-started-event
                        sample-agent-status-event
                        sample-phase-completed-event
                        sample-completed-event])
        (let [detail (persistence/load-workflow-detail sample-wf-id {:dir dir})]
          (is (some? detail))
          (is (= 1 (count (:phases detail))))
          (is (= :analyze (:phase (first (:phases detail)))))
          (is (= :success (:status (first (:phases detail)))))
          (is (= 900000 (:duration-ms detail))))
        (finally (cleanup! dir))))))

(deftest load-workflows-populates-detail-snapshot
  (testing "load-workflows includes :detail-snapshot on each workflow"
    (let [dir (temp-dir)]
      (try
        (write-events! dir sample-wf-id
                       [sample-started-event
                        sample-phase-started-event
                        sample-phase-completed-event
                        sample-completed-event])
        (let [workflows (persistence/load-workflows {:dir dir :limit 10})
              wf (first workflows)]
          (is (= 1 (count workflows)))
          (is (some? (:detail-snapshot wf)) ":detail-snapshot must be present")
          (is (= 1 (count (get-in wf [:detail-snapshot :phases])))
              "snapshot should have phases"))
        (finally (cleanup! dir))))))

(deftest indexed-loader-preserves-enough-for-detail
  (testing "load-workflows-indexed retains data for detail navigation"
    (let [dir (temp-dir)]
      (try
        (write-events! dir sample-wf-id
                       [sample-started-event
                        sample-phase-started-event
                        sample-phase-completed-event
                        sample-completed-event])
        ;; First load: builds index
        (let [wfs1 (persistence/load-workflows {:dir dir :limit 10})]
          (persistence/update-index! wfs1 {:dir dir}))
        ;; Second load: from index — snapshot is NOT in index
        (let [wfs2 (persistence/load-workflows-indexed {:dir dir :limit 10})
              wf (first wfs2)]
          (is (= 1 (count wfs2)))
          ;; Index entry won't have detail-snapshot — that's expected
          ;; But reload-workflow-detail should compensate
          (let [detail (persistence/load-workflow-detail (:id wf) {:dir dir})]
            (is (some? detail) "reload from disk should work")
            (is (= 1 (count (:phases detail))))))
        (finally (cleanup! dir))))))

(deftest workflow-detail-loaded-merges-into-model
  (testing ":msg/workflow-detail-loaded updates active detail in model"
    (let [;; Build a model with a workflow, then simulate being in detail view
          base-model (-> (model/init-model)
                         (update/update-model
                          [:msg/workflow-added {:workflow-id sample-wf-id
                                                :name "Test Workflow"
                                                :spec sample-spec}]))
          ;; Manually set up the detail view state (bypassing keybinding dispatch)
          model (-> base-model
                    (assoc :view :workflow-detail)
                    (assoc :detail {:workflow-id sample-wf-id
                                    :phases []
                                    :current-phase nil
                                    :agent-output ""
                                    :evidence nil
                                    :artifacts []
                                    :expanded-nodes #{}
                                    :focused-pane 0}))
          ;; Simulate the reload-workflow-detail effect completing
          detail (persistence/detail-from-events sample-wf-id
                                                  [sample-started-event
                                                   sample-phase-started-event
                                                   sample-agent-status-event
                                                   sample-phase-completed-event])
          updated (update/update-model model
                    [:msg/workflow-detail-loaded
                     {:workflow-id sample-wf-id :detail detail}])]
      (is (= 1 (count (get-in updated [:detail :phases])))
          "detail should have merged phases from loaded data")
      (is (= :analyze (:phase (first (get-in updated [:detail :phases]))))))))

(deftest derive-status-with-unparseable-terminal
  (testing "workflow with only started event derives :running status"
    (let [events [sample-started-event]]
      (is (= :running (persistence/derive-status events)))))

  (testing "workflow with completed event derives :success"
    (let [events [sample-started-event sample-completed-event]]
      (is (= :success (persistence/derive-status events))))))

(deftest event-file-to-workflow-roundtrip
  (testing "event-file->workflow builds a complete workflow summary"
    (let [dir (temp-dir)]
      (try
        (write-events! dir sample-wf-id
                       [sample-started-event
                        sample-phase-started-event
                        sample-phase-completed-event
                        sample-completed-event])
        (let [wf (persistence/event-file->workflow
                  (io/file dir (str sample-wf-id ".edn")))]
          (is (some? wf))
          (is (= sample-wf-id (:id wf)))
          (is (= "Decompose Large Test Files" (:name wf)))
          (is (= :success (:status wf)))
          (is (= 100 (:progress wf)))
          (is (some? (:detail-snapshot wf))))
        (finally (cleanup! dir))))))

(deftest clean-failed-event-roundtrips-through-edn
  (testing "failed events with java.util.Date timestamps serialize as clean EDN"
    (let [serialized (pr-str clean-failed-event)
          parsed (persistence/safe-read-edn serialized)]
      (is (some? parsed) "clean event should parse")
      (is (= :workflow/failed (:event/type parsed)))
      (is (= "NullPointerException in verify phase"
             (:workflow/failure-reason parsed)))
      (is (not (re-find #"#object" serialized))
          "serialized EDN must not contain #object tags")))

  (testing "full lifecycle with clean failed event preserves error in detail"
    (let [dir (temp-dir)]
      (try
        (write-events! dir sample-wf-id
                       [sample-started-event clean-failed-event])
        (let [detail (persistence/load-workflow-detail sample-wf-id {:dir dir})]
          (is (some? detail))
          (is (some? (:error detail)) "error should be preserved"))
        (finally (cleanup! dir))))))
