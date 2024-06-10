(ns blaze.luid-test
  (:require
   [blaze.luid :as luid]
   [blaze.luid-spec]
   [blaze.test-util :as tu]
   [clojure.math :as math]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [java-time.api :as time])
  (:import
   [java.time Clock Instant ZoneId]
   [java.util Random]
   [java.util.concurrent ThreadLocalRandom]))

(set! *warn-on-reflection* true)
(st/instrument)

(test/use-fixtures :each tu/fixture)

(deftest luid-test
  (testing "length is 16 chars"
    (dotimes [_ 1000]
      (is (s/valid? :blaze/luid (luid/luid (time/system-clock) (ThreadLocalRandom/current)))))))

(defn p [k bit]
  (/ (math/pow k 2.0) (* 2.0 (math/pow 2.0 bit))))

(defn n [a p]
  (/ (math/log (- 1 a))
     (math/log (- 1 p))))

(deftest collision-test
  (testing "when generating 1,000 LUID's per millisecond"
    (testing "it takes between 310,000 and 320,000 occasions to reach"
      (testing "a 90% probability of a collision"
        (is (< 310000 (n 0.9 (p 1000 36)) 320000)))))

  (testing "when generating 10,000 LUID's per millisecond"
    (testing "it takes between 3100 and 3200 occasions to reach"
      (testing "a 90% probability of a collision"
        (is (< 3100 (n 0.9 (p 10000 36)) 3200))))))

(def clock (Clock/fixed Instant/EPOCH (ZoneId/of "UTC")))

(defn fixed-random [n]
  (proxy [Random] []
    (nextLong []
      n)))

(deftest generator-test
  (testing "first 2 LUID's"
    (let [gen (luid/generator clock (fixed-random 0))]
      (is (= (luid/head gen) (luid/luid clock (fixed-random 0))))
      (is (= (luid/head (luid/next gen)) (luid/luid clock (fixed-random 1))))))

  (testing "increments timestamp on entropy exhaustion"
    (let [gen (luid/generator clock (fixed-random 0xFFFFFFFFF))]
      (is (= (luid/head gen) (luid/luid clock (fixed-random 0xFFFFFFFFF))))
      (is (= (luid/head (luid/next gen)) (luid/luid (Clock/offset clock (time/millis 1)) (fixed-random 0)))))))
