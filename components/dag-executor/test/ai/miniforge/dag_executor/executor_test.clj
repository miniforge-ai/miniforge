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

(ns ai.miniforge.dag-executor.executor-test
  "Tests for executor.clj — the public façade for dag-executor's pluggable
   task execution backends. Covers:
   - create-executor-registry (cond-> branches)
   - prepare-docker-executor! (ensure-image? false fast path)
   - with-environment (acquire / f / release lifecycle, including exceptions)
   - capture-provenance (pure data transform with stdout/stderr truncation)
   - with-provenance (composition over with-environment)
   - clone-and-checkout! (git command sequencing + error short-circuit)
   - executor-priority constant

   `select-executor` is covered in interface_test.clj (PR #685) and not
   duplicated here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.executor :as sut]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defrecord ^:private MockExecutor [exec-type
                                   acquire-result
                                   execute-results
                                   release-fn
                                   calls]
  proto/TaskExecutor
  (executor-type        [_]              exec-type)
  (available?           [_]              (result/ok {:available? true}))
  (acquire-environment! [_ task-id config]
    (swap! calls conj [:acquire task-id config])
    (or acquire-result
        (result/ok {:environment-id (str "env-" task-id)
                    :type :container
                    :workdir "/work"})))
  (execute!             [_ env-id command opts]
    (swap! calls conj [:execute env-id command opts])
    (let [n (count (filter #(= :execute (first %)) @calls))
          ;; pick the n-th canned result (1-indexed). If we run out, return ok.
          canned (and (sequential? execute-results)
                      (nth execute-results (dec n) nil))]
      (or canned (result/ok {:exit-code 0 :stdout "" :stderr ""}))))
  (copy-to!             [_ _ _ _]        (result/ok {:copied-bytes 0}))
  (copy-from!           [_ _ _ _]        (result/ok {:copied-bytes 0}))
  (release-environment! [_ env-id]
    (swap! calls conj [:release env-id])
    (when release-fn (release-fn env-id))
    (result/ok {:released? true}))
  (environment-status   [_ _]            (result/ok {:status :running}))
  (persist-workspace!   [_ _ _]          (result/ok {:persisted? true}))
  (restore-workspace!   [_ _ _]          (result/ok {:restored? true})))

(defn- mock-executor
  ([exec-type] (mock-executor exec-type {}))
  ([exec-type {:keys [acquire-result execute-results release-fn]}]
   (->MockExecutor exec-type acquire-result execute-results release-fn (atom []))))

;------------------------------------------------------------------------------ Layer 1
;; executor-priority

(deftest executor-priority-test
  (testing "executor-priority lists preferred order: kubernetes → docker → worktree"
    (is (= [:kubernetes :docker :worktree] sut/executor-priority))))

;------------------------------------------------------------------------------ Layer 1
;; create-executor-registry

(deftest create-executor-registry-worktree-only-by-default-test
  (testing "Empty config still yields a worktree fallback (always available)"
    (let [reg (sut/create-executor-registry {})]
      (is (contains? reg :worktree))
      (is (not (contains? reg :kubernetes)))
      (is (not (contains? reg :docker))))))

(deftest create-executor-registry-includes-docker-when-configured-test
  (testing ":docker config slot adds a Docker executor"
    (let [reg (sut/create-executor-registry {:docker {:image "miniforge/task-runner-clojure:latest"}})]
      (is (contains? reg :docker))
      (is (contains? reg :worktree))
      (is (not (contains? reg :kubernetes))))))

(deftest create-executor-registry-includes-kubernetes-when-configured-test
  (testing ":kubernetes config slot adds a Kubernetes executor"
    (let [reg (sut/create-executor-registry {:kubernetes {:namespace "default"
                                                          :image "alpine:latest"}})]
      (is (contains? reg :kubernetes))
      (is (contains? reg :worktree))
      (is (not (contains? reg :docker))))))

(deftest create-executor-registry-all-three-test
  (testing "Both :kubernetes and :docker configs add their executors alongside worktree"
    (let [reg (sut/create-executor-registry
               {:kubernetes {:namespace "default" :image "alpine:latest"}
                :docker     {:image "miniforge/task-runner:latest"}
                :worktree   {:base-path "/tmp/mf"}})]
      (is (= #{:kubernetes :docker :worktree} (set (keys reg)))))))

;------------------------------------------------------------------------------ Layer 1
;; prepare-docker-executor! — ensure-image? false fast path

(deftest prepare-docker-executor!-skip-image-ensure-test
  (testing "When :ensure-image? is false, prepare-docker-executor! returns the
            executor without consulting ensure-image! at all"
    (let [r (sut/prepare-docker-executor! {:ensure-image? false
                                           :image "miniforge/task-runner:latest"})]
      (is (result/ok? r))
      (is (some? (-> r :data :executor)))
      (is (nil? (-> r :data :image-result))))))

(deftest prepare-docker-executor!-uses-image-type-default-test
  (testing "When :image is omitted, prepare-docker-executor! falls back to the
            :image-type's default in task-runner-images"
    (let [r (sut/prepare-docker-executor! {:ensure-image? false
                                           :image-type :clojure})]
      (is (result/ok? r))
      ;; The executor record's config should carry the resolved default image.
      (let [exec (-> r :data :executor)
            default-image (get-in sut/task-runner-images [:clojure :image])]
        (is (= default-image (:image (:config exec))))))))

;------------------------------------------------------------------------------ Layer 1
;; with-environment

(deftest with-environment-runs-f-and-releases-test
  (testing "with-environment acquires, runs f, and releases. f's return is returned."
    (let [exec (mock-executor :worktree)
          tid  (random-uuid)
          ret  (sut/with-environment exec tid {:branch "main"}
                 (fn [env]
                   (is (= (str "env-" tid) (:environment-id env)))
                   :work-result))]
      (is (= :work-result ret))
      ;; Both acquire and release happened, exactly once each.
      (let [acq (count (filter #(= :acquire (first %)) @(:calls exec)))
            rel (count (filter #(= :release (first %)) @(:calls exec)))]
        (is (= 1 acq))
        (is (= 1 rel))))))

(deftest with-environment-releases-on-exception-test
  (testing "If f throws, with-environment still releases the environment.
            The exception propagates out."
    (let [exec (mock-executor :worktree)
          tid  (random-uuid)]
      (is (thrown? RuntimeException
                   (sut/with-environment exec tid {}
                     (fn [_env] (throw (RuntimeException. "boom"))))))
      ;; Release was called even though f threw.
      (is (= 1 (count (filter #(= :release (first %)) @(:calls exec))))))))

(deftest with-environment-acquire-failure-skips-release-test
  (testing "If acquire fails, with-environment returns the err result and
            never invokes f or release"
    (let [acquire-err (result/err :acquire-failed "no slot")
          f-called?   (atom false)
          exec        (mock-executor :docker {:acquire-result acquire-err})
          ret         (sut/with-environment exec (random-uuid) {}
                        (fn [_env] (reset! f-called? true)))]
      (is (result/err? ret))
      (is (false? @f-called?))
      (is (zero? (count (filter #(= :release (first %)) @(:calls exec))))))))

;------------------------------------------------------------------------------ Layer 1
;; capture-provenance

(defn- inst [iso]
  (java.time.Instant/parse iso))

(deftest capture-provenance-required-fields-test
  (testing "Returned map carries the documented :provenance/* keys"
    (let [exec (mock-executor :docker)
          rec (sut/capture-provenance "task-1" exec
                                      {:commands-executed ["git status"]
                                       :started-at        (inst "2026-04-30T00:00:00Z")
                                       :completed-at      (inst "2026-04-30T00:00:01Z")
                                       :exit-code         0
                                       :stdout            "ok"
                                       :stderr            ""
                                       :image-digest      "sha256:abc"
                                       :environment-id    "env-1"})]
      (is (= "task-1"            (:provenance/task-id rec)))
      (is (= :docker             (:provenance/executor-type rec)))
      (is (= "sha256:abc"        (:provenance/image-digest rec)))
      (is (= ["git status"]      (:provenance/commands-executed rec)))
      (is (inst? (:provenance/started-at rec)))
      (is (inst? (:provenance/completed-at rec)))
      (is (= 1000                (:provenance/duration-ms rec)))
      (is (= 0                   (:provenance/exit-code rec)))
      (is (= "ok"                (:provenance/stdout-summary rec)))
      (is (= ""                  (:provenance/stderr-summary rec)))
      (is (= "env-1"             (:provenance/environment-id rec))))))

(deftest capture-provenance-truncates-stdout-stderr-test
  (testing "Long stdout/stderr are truncated to 500 chars"
    (let [big (apply str (repeat 800 \x))
          rec (sut/capture-provenance "t" (mock-executor :worktree)
                                      {:stdout big :stderr big})]
      (is (= 500 (count (:provenance/stdout-summary rec))))
      (is (= 500 (count (:provenance/stderr-summary rec)))))))

(deftest capture-provenance-handles-nil-fields-test
  (testing "Missing/nil fields produce safe defaults rather than throwing"
    (let [rec (sut/capture-provenance "t" (mock-executor :worktree) {})]
      (is (= "t" (:provenance/task-id rec)))
      (is (= 0 (:provenance/duration-ms rec)))
      (is (= -1 (:provenance/exit-code rec)))
      (is (= "" (:provenance/stdout-summary rec)))
      (is (= "" (:provenance/stderr-summary rec)))
      (is (= "" (:provenance/environment-id rec)))
      (is (vector? (:provenance/commands-executed rec)))
      (is (empty? (:provenance/commands-executed rec))))))

(deftest capture-provenance-task-id-stringified-test
  (testing "Non-string task-id (e.g. UUID) is coerced to string"
    (let [tid (random-uuid)
          rec (sut/capture-provenance tid (mock-executor :worktree) {})]
      (is (= (str tid) (:provenance/task-id rec))))))

;------------------------------------------------------------------------------ Layer 1
;; with-provenance

(deftest with-provenance-returns-result-and-provenance-test
  (testing "with-provenance returns {:result <f-return> :provenance <captured>}.
            The provenance reflects whatever the inner f reset! into prov-atom."
    (let [exec (mock-executor :worktree)
          tid  (random-uuid)
          started (inst "2026-04-30T00:00:00Z")
          completed (inst "2026-04-30T00:00:00.500Z")
          out (sut/with-provenance exec tid {}
                (fn [env prov]
                  (reset! prov {:commands-executed ["echo hi"]
                                :started-at        started
                                :completed-at      completed
                                :exit-code         0
                                :stdout            "hi"
                                :stderr            ""
                                :environment-id    (:environment-id env)})
                  :inner-ok))]
      (is (= :inner-ok (:result out)))
      (let [prov (:provenance out)]
        (is (= (str tid)        (:provenance/task-id prov)))
        (is (= :worktree        (:provenance/executor-type prov)))
        (is (= ["echo hi"]      (:provenance/commands-executed prov)))
        (is (= 0                (:provenance/exit-code prov)))
        (is (= 500              (:provenance/duration-ms prov)))
        (is (= "hi"             (:provenance/stdout-summary prov)))))))

(deftest with-provenance-untouched-prov-atom-defaults-test
  (testing "When f never reset!s prov-atom, capture-provenance gracefully
            produces a record with empty/-1/0 defaults"
    (let [exec (mock-executor :worktree)
          out (sut/with-provenance exec (random-uuid) {}
                (fn [_env _prov] :no-prov-recorded))]
      (is (= :no-prov-recorded (:result out)))
      (let [prov (:provenance out)]
        (is (= 0  (:provenance/duration-ms prov)))
        (is (= -1 (:provenance/exit-code prov)))
        (is (empty? (:provenance/commands-executed prov)))))))

;------------------------------------------------------------------------------ Layer 1
;; clone-and-checkout!

(deftest clone-and-checkout!-success-test
  (testing "Successful clone + checkout returns ok with :cloned? and :branch.
            Two commands are issued in order: git clone then git checkout."
    (let [exec (mock-executor :worktree)
          ret  (sut/clone-and-checkout! exec "env-1"
                                        "https://example.com/r.git"
                                        "feature/x"
                                        {})
          cmds (->> @(:calls exec)
                    (filter #(= :execute (first %)))
                    (map #(nth % 2)))]
      (is (result/ok? ret))
      (is (true? (-> ret :data :cloned?)))
      (is (= "feature/x" (-> ret :data :branch)))
      (is (= 2 (count cmds)))
      (is (str/starts-with? (first cmds) "git clone "))
      (is (str/includes? (first cmds) "https://example.com/r.git"))
      (is (= "git checkout feature/x" (second cmds))))))

(deftest clone-and-checkout!-with-depth-option-test
  (testing "Supplying :depth adds --depth N to the clone command"
    (let [exec (mock-executor :worktree)
          _ (sut/clone-and-checkout! exec "env-1"
                                     "https://example.com/r.git"
                                     "main"
                                     {:depth 5})
          clone-cmd (-> @(:calls exec)
                        (->> (filter #(= :execute (first %))))
                        first
                        (nth 2))]
      (is (str/includes? clone-cmd "--depth 5")))))

(deftest clone-and-checkout!-clone-failure-short-circuits-test
  (testing "If git clone returns a non-zero exit code, checkout is never run
            and the clone failure is returned"
    (let [exec (mock-executor :worktree
                              {:execute-results
                               [(result/ok {:exit-code 128 :stdout "" :stderr "fatal"})]})
          ret  (sut/clone-and-checkout! exec "env-1"
                                        "https://example.com/r.git"
                                        "main"
                                        {})
          cmds (->> @(:calls exec)
                    (filter #(= :execute (first %)))
                    (map #(nth % 2)))]
      ;; Only the clone command was issued.
      (is (= 1 (count cmds)))
      (is (str/starts-with? (first cmds) "git clone"))
      ;; The clone result (exit-code 128) is what the function returns.
      (is (= 128 (:exit-code (:data ret)))))))

(deftest clone-and-checkout!-clone-err-result-short-circuits-test
  (testing "An err result from execute! also short-circuits"
    (let [exec (mock-executor :worktree
                              {:execute-results
                               [(result/err :exec-failed "no docker")]})
          ret  (sut/clone-and-checkout! exec "env-1"
                                        "https://example.com/r.git"
                                        "main"
                                        {})]
      (is (result/err? ret))
      (is (= 1 (count (filter #(= :execute (first %)) @(:calls exec))))))))

(deftest clone-and-checkout!-checkout-failure-returns-checkout-result-test
  (testing "If clone succeeds but checkout fails, the checkout result is returned"
    (let [exec (mock-executor :worktree
                              {:execute-results
                               [(result/ok {:exit-code 0 :stdout "" :stderr ""})
                                (result/ok {:exit-code 1 :stdout "" :stderr "branch not found"})]})
          ret  (sut/clone-and-checkout! exec "env-1"
                                        "https://example.com/r.git"
                                        "missing"
                                        {})]
      ;; Both commands ran.
      (is (= 2 (count (filter #(= :execute (first %)) @(:calls exec)))))
      ;; The checkout result is what we get back.
      (is (= 1 (:exit-code (:data ret)))))))
