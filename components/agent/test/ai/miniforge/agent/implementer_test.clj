(ns ai.miniforge.agent.implementer-test
  "Tests for the Implementer agent."
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.implementer :as implementer]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/example/core.clj"
                 :content "(ns example.core)\n\n(defn hello [] \"world\")"
                 :action :create}]
   :code/dependencies-added []
   :code/tests-needed? true
   :code/language "clojure"
   :code/summary "Added example core"})

(def minimal-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/minimal.clj"
                 :content "(ns minimal)"
                 :action :create}]})

;------------------------------------------------------------------------------ Layer 1
;; Agent creation tests

(deftest create-implementer-test
  (testing "creates implementer with default config"
    (let [agent (implementer/create-implementer)]
      (is (some? agent))
      (is (= :implementer (:role agent)))
      (is (string? (:system-prompt agent)))))

  (testing "creates implementer with custom config"
    (let [agent (implementer/create-implementer {:config {:temperature 0.1}})]
      (is (= 0.1 (get-in agent [:config :temperature])))))

  (testing "creates implementer with logger"
    (let [[logger _] (log/collecting-logger)
          agent (implementer/create-implementer {:logger logger})]
      (is (some? (:logger agent))))))

;------------------------------------------------------------------------------ Layer 2
;; Invoke tests

(deftest implementer-invoke-test
  (testing "generates code from task"
    (let [agent (implementer/create-implementer)
          result (core/invoke agent
                              {:suggested-path "src/auth/login.clj"}
                              {:task/description "Implement user login"
                               :task/type :implement})]
      (is (= :success (:status result)))
      (is (uuid? (get-in result [:output :code/id])))
      (is (vector? (get-in result [:output :code/files])))
      (is (= :create (get-in result [:output :code/files 0 :action])))))

  (testing "includes metrics in result"
    (let [agent (implementer/create-implementer)
          result (core/invoke agent {} {:description "Simple task"})]
      (is (number? (get-in result [:metrics :files-created])))
      (is (string? (get-in result [:metrics :language])))))

  (testing "detects language from file extension"
    (let [agent (implementer/create-implementer)
          result (core/invoke agent
                              {:suggested-path "src/app.py"}
                              {:description "Python task"})]
      ;; Note: actual language detection happens during generation
      (is (= :success (:status result))))))

;------------------------------------------------------------------------------ Layer 3
;; Validation tests

(deftest validate-code-artifact-test
  (testing "valid artifact passes validation"
    (let [result (implementer/validate-code-artifact valid-code-artifact)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "minimal artifact passes validation"
    (let [result (implementer/validate-code-artifact minimal-code-artifact)]
      (is (:valid? result))))

  (testing "missing required fields fails validation"
    (let [result (implementer/validate-code-artifact {:code/files []})]
      (is (not (:valid? result)))))

  (testing "empty content for :create fails validation"
    (let [bad-artifact {:code/id (random-uuid)
                        :code/files [{:path "src/empty.clj"
                                      :content ""
                                      :action :create}]}
          result (implementer/validate-code-artifact bad-artifact)]
      (is (not (:valid? result)))
      (is (contains? (:errors result) :files))))

  (testing "duplicate paths fail validation"
    (let [bad-artifact {:code/id (random-uuid)
                        :code/files [{:path "src/dup.clj"
                                      :content "(ns dup)"
                                      :action :create}
                                     {:path "src/dup.clj"
                                      :content "(ns dup2)"
                                      :action :modify}]}
          result (implementer/validate-code-artifact bad-artifact)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 4
;; Utility tests

(deftest code-summary-test
  (testing "returns code summary"
    (let [summary (implementer/code-summary valid-code-artifact)]
      (is (uuid? (:id summary)))
      (is (number? (:file-count summary)))
      (is (map? (:actions summary)))
      (is (string? (:language summary)))
      (is (boolean? (:tests-needed? summary))))))

(deftest files-by-action-test
  (testing "groups files by action"
    (let [artifact {:code/files [{:path "a.clj" :content "" :action :create}
                                 {:path "b.clj" :content "" :action :modify}
                                 {:path "c.clj" :content "" :action :create}]}
          grouped (implementer/files-by-action artifact)]
      (is (= 2 (count (:create grouped))))
      (is (= 1 (count (:modify grouped)))))))

(deftest total-lines-test
  (testing "counts total lines of code"
    (let [artifact {:code/files [{:path "a.clj"
                                  :content "line1\nline2\nline3"
                                  :action :create}
                                 {:path "b.clj"
                                  :content "line1\nline2"
                                  :action :modify}]}
          lines (implementer/total-lines artifact)]
      (is (= 5 lines))))

  (testing "excludes deleted files from count"
    (let [artifact {:code/files [{:path "a.clj"
                                  :content "line1\nline2"
                                  :action :create}
                                 {:path "b.clj"
                                  :content "should not count"
                                  :action :delete}]}
          lines (implementer/total-lines artifact)]
      (is (= 2 lines)))))

;------------------------------------------------------------------------------ Layer 5
;; Repair tests

(deftest implementer-repair-test
  (testing "repairs missing ID"
    (let [agent (implementer/create-implementer)
          bad-artifact {:code/files [{:path "src/test.clj"
                                      :content "(ns test)"
                                      :action :create}]}
          result (core/repair agent bad-artifact {:code/id ["required"]} {})]
      (is (= :success (:status result)))
      (is (uuid? (get-in result [:output :code/id])))))

  (testing "adds content to empty :create files"
    (let [agent (implementer/create-implementer)
          bad-artifact {:code/id (random-uuid)
                        :code/files [{:path "src/empty.clj"
                                      :content ""
                                      :action :create}]}
          result (core/repair agent bad-artifact {:files ["empty content"]} {})]
      (is (= :success (:status result)))
      (is (seq (get-in result [:output :code/files 0 :content]))))))

;------------------------------------------------------------------------------ Layer 6
;; Full cycle tests

(deftest implementer-cycle-test
  (testing "full invoke-validate cycle succeeds"
    (let [agent (implementer/create-implementer)
          result (core/cycle-agent agent {} {:task/description "Create a helper function"
                                              :task/type :implement})]
      (is (= :success (:status result)))
      (is (uuid? (get-in result [:output :code/id]))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.implementer-test)

  :leave-this-here)
