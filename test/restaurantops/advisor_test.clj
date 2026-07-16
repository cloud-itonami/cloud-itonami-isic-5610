(ns restaurantops.advisor-test
  "Unit tests of `restaurantops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [restaurantops.advisor :as adv]
            [restaurantops.store :as store]))

(def db (store/seed-db))

(deftest propose-service-record-shape
  (testing "service-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-service-record
                           :location-id "location-1"
                           :patch {:orders 42 :table-turns 12 :menu-items ["ramen"]}})]
      (is (= :log-service-record (:op p)))
      (is (= "location-1" (:location-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :location-id)))))

(deftest propose-staffing-operation-shape
  (testing "staffing-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-staffing-operation
                           :location-id "location-2"
                           :patch {:shift "dinner-prep" :date "2026-07-20"}})]
      (is (= :schedule-staffing-operation (:op p)))
      (is (= "location-2" (:location-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :location-id "location-1"
                           :patch {:item "disposable takeout containers" :quantity 500 :estimated-cost 130.0}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-food-safety-concern-shape
  (testing "food-safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-food-safety-concern
                           :location-id "location-1"
                           :patch {:concern "allergen mismatch on table 7"}})]
      (is (= :flag-food-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-service-record :schedule-staffing-operation :coordinate-supply-order
                :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :location-id "location-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-service-record :schedule-staffing-operation :coordinate-supply-order
                :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :location-id "location-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
