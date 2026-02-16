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

(ns ai.miniforge.tui-views.completion-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.pr-sync.interface :as pr-sync]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.update.command :as command]
   [ai.miniforge.tui-views.update.completion :as completion]
   [ai.miniforge.tui-views.test-util :as util]))

;; ---------------------------------------------------------------------------
;; Unit tests: command/complete-command-name
;; ---------------------------------------------------------------------------

(deftest complete-command-name-test
  (testing "Empty partial returns all commands"
    (let [results (command/complete-command-name "")]
      (is (pos? (count results)))
      (is (some #{"theme"} results))
      (is (some #{"view"} results))
      (is (some #{"quit"} results))))

  (testing "Partial 'th' matches 'theme'"
    (let [results (command/complete-command-name "th")]
      (is (some #{"theme"} results))
      (is (not (some #{"view"} results)))))

  (testing "Partial 'v' matches 'view'"
    (let [results (command/complete-command-name "v")]
      (is (some #{"view"} results))))

  (testing "Non-matching partial returns empty"
    (let [results (command/complete-command-name "zzz")]
      (is (empty? results)))))

;; ---------------------------------------------------------------------------
;; Unit tests: command/compute-completions
;; ---------------------------------------------------------------------------

(deftest compute-completions-test
  (testing ":theme with no arg shows all themes"
    (let [result (command/compute-completions (model/init-model) ":theme ")]
      (is (some? result))
      (is (pos? (count (:completions result))))
      (is (some #{"browse"} (:completions result)))
      (is (some #{"dark"} (:completions result)))
      (is (some #{"light"} (:completions result)))
      (is (some #{"high-contrast"} (:completions result)))))

  (testing ":theme d filters to dark"
    (let [result (command/compute-completions (model/init-model) ":theme d")]
      (is (some #{"dark"} (:completions result)))
      (is (not (some #{"light"} (:completions result))))))

  (testing ":view shows all view names"
    (let [result (command/compute-completions (model/init-model) ":view ")]
      (is (some? result))
      (is (some #{"evidence"} (:completions result)))
      (is (some #{"pr-fleet"} (:completions result)))))

  (testing "Command without :completions returns nil"
    (let [result (command/compute-completions (model/init-model) ":quit ")]
      (is (nil? result))))

  (testing ":add-repo merges local configured and browsed remote repos"
    (with-redefs [pr-sync/get-configured-repos (fn [] ["acme/local" "shared/repo"])]
      (let [m (assoc (model/init-model) :browse-repos ["acme/remote" "shared/repo"])
            result (command/compute-completions m ":add-repo ")]
        (is (= ["acme/local" "acme/remote" "browse" "shared/repo"] (:completions result)))
        (is (nil? (:side-effect result))))))

  (testing ":add-repo requests browse side-effect when remote cache is empty"
    (with-redefs [pr-sync/get-configured-repos (fn [] [])]
      (let [result (command/compute-completions (model/init-model) ":add-repo ")]
        (is (= {:type :browse-repos :provider :all} (:side-effect result)))))))

;; ---------------------------------------------------------------------------
;; Unit tests: completion/dismiss
;; ---------------------------------------------------------------------------

(deftest dismiss-test
  (testing "dismiss clears completion state"
    (let [m (-> (model/init-model)
                (assoc :completing? true
                       :completions ["a" "b" "c"]
                       :completion-idx 1))
          result (completion/dismiss m)]
      (is (false? (:completing? result)))
      (is (empty? (:completions result)))
      (is (nil? (:completion-idx result))))))

;; ---------------------------------------------------------------------------
;; Unit tests: completion/next-completion, prev-completion
;; ---------------------------------------------------------------------------

(deftest next-prev-completion-test
  (testing "next-completion advances index"
    (let [m (-> (model/init-model)
                (assoc :completing? true
                       :completions ["a" "b" "c"]
                       :completion-idx 0))
          result (completion/next-completion m)]
      (is (= 1 (:completion-idx result)))))

  (testing "next-completion wraps around"
    (let [m (-> (model/init-model)
                (assoc :completing? true
                       :completions ["a" "b" "c"]
                       :completion-idx 2))
          result (completion/next-completion m)]
      (is (= 0 (:completion-idx result)))))

  (testing "prev-completion goes back"
    (let [m (-> (model/init-model)
                (assoc :completing? true
                       :completions ["a" "b" "c"]
                       :completion-idx 2))
          result (completion/prev-completion m)]
      (is (= 1 (:completion-idx result)))))

  (testing "prev-completion wraps around"
    (let [m (-> (model/init-model)
                (assoc :completing? true
                       :completions ["a" "b" "c"]
                       :completion-idx 0))
          result (completion/prev-completion m)]
      (is (= 2 (:completion-idx result))))))

;; ---------------------------------------------------------------------------
;; Unit tests: completion/accept
;; ---------------------------------------------------------------------------

(deftest accept-test
  (testing "accept fills command name and adds space"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":th"
                       :completing? true
                       :completions ["theme"]
                       :completion-idx 0))
          result (completion/accept m)]
      (is (= ":theme " (:command-buf result)))
      (is (false? (:completing? result)))))

  (testing "accept fills argument"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":theme d"
                       :completing? true
                       :completions ["dark"]
                       :completion-idx 0))
          result (completion/accept m)]
      (is (= ":theme dark" (:command-buf result)))
      (is (false? (:completing? result)))))

  (testing "accept with nil index dismisses"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":theme "
                       :completing? true
                       :completions ["dark" "light"]
                       :completion-idx nil))
          result (completion/accept m)]
      (is (false? (:completing? result))))))

;; ---------------------------------------------------------------------------
;; Unit tests: completion/handle-tab
;; ---------------------------------------------------------------------------

(deftest handle-tab-test
  (testing "Tab with partial command opens completion popup"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":th"))
          result (completion/handle-tab m)]
      (is (true? (:completing? result)))
      (is (some #{"theme"} (:completions result)))
      (is (= 0 (:completion-idx result)))))

  (testing "Tab when already completing cycles forward"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":th"
                       :completing? true
                       :completions ["theme"]
                       :completion-idx 0))
          result (completion/handle-tab m)]
      ;; Wraps since there's only 1 item
      (is (= 0 (:completion-idx result)))))

  (testing "Tab with no matches is no-op"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":zzz"))
          result (completion/handle-tab m)]
      (is (false? (:completing? result)))))

  (testing "Tab on exact :add-repo enters argument completion mode"
    (with-redefs [pr-sync/get-configured-repos (fn [] ["acme/local"])]
      (let [m (-> (model/init-model)
                  (assoc :mode :command
                         :command-buf ":add-repo"))
            result (completion/handle-tab m)]
        (is (= ":add-repo " (:command-buf result)))
        (is (true? (:completing? result)))
        (is (some #{"browse"} (:completions result)))
        (is (some #{"acme/local"} (:completions result))))))

  (testing "Tab on :add-repo triggers browse side-effect when cache is empty"
    (with-redefs [pr-sync/get-configured-repos (fn [] [])]
      (let [m (-> (model/init-model)
                  (assoc :mode :command
                         :command-buf ":add-repo "))
            result (completion/handle-tab m)]
        (is (= {:type :browse-repos :provider :all} (:side-effect result)))
        (is (true? (:browse-repos-loading? result)))
        (is (true? (:completing? result)))
        (is (= ["browse"] (:completions result)))))))

;; ---------------------------------------------------------------------------
;; Unit tests: completion/handle-shift-tab
;; ---------------------------------------------------------------------------

(deftest handle-shift-tab-test
  (testing "Shift+Tab with partial command opens completion popup at last item"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":"))
          result (completion/handle-shift-tab m)]
      (is (true? (:completing? result)))
      (is (= (dec (count (:completions result))) (:completion-idx result)))))

  (testing "Shift+Tab on exact :theme enters argument completion mode"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":theme"))
          result (completion/handle-shift-tab m)]
      (is (= ":theme " (:command-buf result)))
      (is (true? (:completing? result)))
      (is (some #{"browse"} (:completions result)))))

  (testing "Shift+Tab when already completing cycles backward"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":theme "
                       :completing? true
                       :completions ["dark" "light" "high-contrast"]
                       :completion-idx 0))
          result (completion/handle-shift-tab m)]
      (is (= 2 (:completion-idx result))))))

(deftest browse-accept-test
  (testing "Accepting 'browse' on :add-repo expands into repo choices instead of literal browse arg"
    (with-redefs [pr-sync/get-configured-repos (fn [] ["acme/local"])]
      (let [m (-> (model/init-model)
                  (assoc :mode :command
                         :command-buf ":add-repo "
                         :completing? true
                         :completions ["acme/local" "browse"]
                         :completion-idx 1))
            result (completion/accept m)]
        (is (= ":add-repo " (:command-buf result)))
        (is (true? (:completing? result)))
        (is (= ["acme/local"] (:completions result)))
        (is (= 0 (:completion-idx result))))))

  (testing "Accepting 'browse' on :theme expands into theme choices"
    (let [m (-> (model/init-model)
                (assoc :mode :command
                       :command-buf ":theme "
                       :completing? true
                       :completions ["browse" "dark" "light"]
                       :completion-idx 0))
          result (completion/accept m)]
      (is (= ":theme " (:command-buf result)))
      (is (true? (:completing? result)))
      (is (some #{"dark"} (:completions result)))
      (is (not (some #{"browse"} (:completions result)))))))

;; ---------------------------------------------------------------------------
;; Integration tests: Tab completion through update-model
;; ---------------------------------------------------------------------------

(deftest tab-completion-integration-test
  (testing "Full Tab completion flow: type, tab, select, accept"
    (let [m (util/apply-updates (util/fresh-model)
              [;; Enter command mode
               [:input {:key :key/colon :char \:}]
               ;; Type "th"
               [:input {:key nil :char \t}]
               [:input {:key :key/h :char \h}]
               ;; Tab to open completions
               [:input :key/tab]])]
      (is (util/mode-is? m :command))
      (is (true? (:completing? m)))
      (is (some #{"theme"} (:completions m)))
      ;; Accept with Enter
      (let [m2 (update/update-model m [:input :key/enter])]
        (is (= ":theme " (:command-buf m2)))
        (is (false? (:completing? m2))))))

  (testing "Esc dismisses completions without exiting command mode"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]
               [:input {:key nil :char \t}]
               [:input {:key :key/h :char \h}]
               [:input :key/tab]         ;; open completions
               [:input :key/escape]])]   ;; dismiss
      (is (util/mode-is? m :command))
      (is (false? (:completing? m)))))

  (testing "Typing a char dismisses completions"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]
               [:input {:key nil :char \t}]
               [:input {:key :key/h :char \h}]
               [:input :key/tab]                  ;; open completions
               [:input {:key :key/e :char \e}]])] ;; type 'e' -> dismiss
      (is (util/mode-is? m :command))
      (is (false? (:completing? m)))
      (is (= ":the" (:command-buf m)))))

  (testing "Backspace dismisses completions"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]
               [:input {:key nil :char \t}]
               [:input {:key :key/h :char \h}]
               [:input :key/tab]            ;; open completions
               [:input :key/backspace]])]   ;; backspace -> dismiss
      (is (util/mode-is? m :command))
      (is (false? (:completing? m)))
      (is (= ":t" (:command-buf m)))))

  (testing "Down/Up arrows navigate completions"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]
               ;; Empty partial -> all commands
               [:input :key/tab]])]
      (is (true? (:completing? m)))
      (is (= 0 (:completion-idx m)))
      ;; Down arrow
      (let [m2 (update/update-model m [:input :key/down])]
        (is (= 1 (:completion-idx m2))))
      ;; Up arrow wraps to last
      (let [m2 (update/update-model m [:input :key/up])]
        (is (= (dec (count (:completions m))) (:completion-idx m2))))))

  (testing "Shift+Tab opens command completions selecting the last item"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]
               [:input :key/shift-tab]])]
      (is (true? (:completing? m)))
      (is (= (dec (count (:completions m))) (:completion-idx m)))))

  (testing "Enter on bare :theme opens theme picker instead of executing"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]
               [:input {:key nil :char \t}]
               [:input {:key :key/h :char \h}]
               [:input {:key :key/e :char \e}]
               [:input {:key nil :char \m}]
               [:input {:key :key/e :char \e}]
               [:input :key/enter]])]
      (is (util/mode-is? m :command))
      (is (= ":theme " (:command-buf m)))
      (is (true? (:completing? m)))
      (is (some #{"browse"} (:completions m)))))

  (testing "Enter on bare :add-repo opens repo picker instead of executing"
    (with-redefs [pr-sync/get-configured-repos (fn [] ["acme/local"])]
      (let [m (util/apply-updates (util/fresh-model)
                [[:input {:key :key/colon :char \:}]
                 [:input {:key :key/a :char \a}]
                 [:input {:key :key/d :char \d}]
                 [:input {:key :key/d :char \d}]
                 [:input {:key nil :char \-}]
                 [:input {:key :key/r :char \r}]
                 [:input {:key :key/e :char \e}]
                 [:input {:key nil :char \p}]
                 [:input {:key :key/o :char \o}]
                 [:input :key/enter]])]
        (is (util/mode-is? m :command))
        (is (= ":add-repo " (:command-buf m)))
        (is (true? (:completing? m)))
        (is (some #{"browse"} (:completions m)))
        (is (some #{"acme/local"} (:completions m)))))))
