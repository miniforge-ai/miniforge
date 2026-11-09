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
(ns ai.miniforge.subprocess.interface-test
  (:require
   [ai.miniforge.subprocess.capture :as capture]
   [ai.miniforge.subprocess.interface :as sut]
   [ai.miniforge.subprocess.messages :as msg]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.io BufferedInputStream ByteArrayInputStream PipedInputStream PipedOutputStream)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private limit-bytes
  "A capture limit a few bytes of input can reach."
  16)

(def ^{:stratum 0} ^:private finish-wait-ms
  "Ceiling on waiting for a drain over an in-memory stream, which finishes
   in microseconds; reaching it means the drain hung."
  1000)

(def ^{:stratum 0} ^:private multi-read-bytes
  "Three reads' worth of input, so more than one chunk lands past the
   limit and the once-only overflow hook is put to the test."
  (* 3 capture/read-chunk-bytes))

(def ^{:stratum 0} ^:private shipped-limit-bytes
  "The limit config/subprocess/capture.edn ships with: 64 MiB."
  (* 64 1024 1024))

(defn- ^{:stratum 0} numbered-bytes
  "`n` bytes whose values follow their positions, so a kept head can be told
   apart from any other slice."
  [n]
  (byte-array (map #(mod % Byte/MAX_VALUE) (range n))))

(defn- ^{:stratum 0} counting-hook
  [calls]
  (fn [] (swap! calls inc)))

(defn- ^{:stratum 0} closing-hook
  [^java.io.InputStream stream]
  (fn [] (.close stream)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} finished-drain
  "Drain `input` with `limit-bytes`, counting :on-overflow calls in
   `overflow-calls`, and wait for the drain to reach end-of-stream."
  [^bytes input overflow-calls]
  (let [handle (sut/drain-stream (ByteArrayInputStream. input)
                                 {:max-bytes   limit-bytes
                                  :on-overflow (counting-hook overflow-calls)})]
    (deref (:future handle) finish-wait-ms ::hung)
    handle))

(deftest ^{:stratum 1} drain-past-limit-with-stopping-hook-test
  (testing "given a hook that closes the stream, as Process.destroy does →
            the drain ends cleanly with the head kept"
    (let [input  (numbered-bytes (* 4 limit-bytes))
          stream (BufferedInputStream. (ByteArrayInputStream. input))
          handle (sut/drain-stream stream {:max-bytes   limit-bytes
                                           :on-overflow (closing-hook stream)})]
      (is (nil? (deref (:future handle) finish-wait-ms ::hung))
          "the read failing on the closed stream is not the drain's failure")
      (is (sut/overflowed? handle))
      (is (= (take limit-bytes input) (seq (sut/drained-bytes handle)))))))

(deftest ^{:stratum 1} drain-closed-stream-test
  (testing "given a stream closed under the drain before the limit, as a
            child's other stream is when an overflow stops the child → the
            drain ends cleanly, not overflowed"
    (let [stream (doto (BufferedInputStream. (ByteArrayInputStream. (numbered-bytes limit-bytes)))
                   (.close))
          handle (sut/drain-stream stream {:max-bytes limit-bytes})]
      (is (nil? (deref (:future handle) finish-wait-ms ::hung)))
      (is (not (sut/overflowed? handle)))
      (is (zero? (alength (sut/drained-bytes handle)))))))

(deftest ^{:stratum 1} drain-default-limit-test
  (testing "given no :max-bytes → the configured limit applies"
    (is (= shipped-limit-bytes sut/max-captured-bytes))
    (let [handle (sut/drain-stream (ByteArrayInputStream. (byte-array 0)))]
      (deref (:future handle) finish-wait-ms ::hung)
      (is (= sut/max-captured-bytes (:max-bytes handle)))
      (is (= "" (sut/drained-text handle))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} drain-within-limit-test
  (doseq [n [(dec limit-bytes) limit-bytes]]
    (testing (str "given " n " bytes and a " limit-bytes "-byte limit → all kept")
      (let [input  (numbered-bytes n)
            calls  (atom 0)
            handle (finished-drain input calls)]
        (is (future-done? (:future handle)))
        (is (= (seq input) (seq (sut/drained-bytes handle))))
        (is (= (String. input "UTF-8") (sut/drained-text handle)))
        (is (not (sut/overflowed? handle)))
        (is (zero? @calls))))))

(deftest ^{:stratum 2} drain-past-limit-test
  (testing "given more bytes than the limit → head kept, rest read and dropped"
    (let [input  (numbered-bytes multi-read-bytes)
          calls  (atom 0)
          handle (finished-drain input calls)]
      (is (future-done? (:future handle))
          "the drain reads to end-of-stream, so the writer never blocks")
      (is (= (take limit-bytes input) (seq (sut/drained-bytes handle))))
      (is (sut/overflowed? handle))
      (is (= 1 @calls) ":on-overflow runs once, on the first chunk that does not fit")
      (is (= (str (String. (byte-array (take limit-bytes input)) "UTF-8")
                  (msg/t :capture/cut-note {:bytes limit-bytes}))
             (sut/drained-text handle))
          "the text says it was cut"))))

(deftest ^{:stratum 2} cancel-drain-test
  (testing "given a drain parked on a pipe nobody writes → cancel closes the
            stream (a thread parked in a process pipe read wakes only on
            that) and cancels the future"
    (with-open [writer (PipedOutputStream.)]
      (let [reader (PipedInputStream. writer)
            handle (sut/drain-stream reader)]
        (sut/cancel-drain! handle)
        (is (future-cancelled? (:future handle)))
        (is (thrown? java.io.IOException (.read reader))
            "the stream is closed, not just the future cancelled"))))
  (testing "given a finished drain → cancel is a no-op and the bytes stay"
    (let [input  (numbered-bytes limit-bytes)
          handle (finished-drain input (atom 0))]
      (sut/cancel-drain! handle)
      (is (= (seq input) (seq (sut/drained-bytes handle)))))))
