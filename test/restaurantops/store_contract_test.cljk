(ns restaurantops.store-contract-test
  "Contract tests for `restaurantops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [restaurantops.store :as store]))

(deftest mem-store-location-lookup
  (testing "MemStore can store and retrieve locations by ID (string keys)"
    (let [locations {"l1" {:location-id "l1" :name "Alice's Bistro" :registered? true :verified? true}}
          s (store/mem-store locations)]
      (is (some? (store/location s "l1")))
      (is (nil? (store/location s "l99"))))))

(deftest mem-store-all-locations
  (testing "MemStore returns all locations in sorted order"
    (let [locations {"l2" {:location-id "l2" :name "Bob's Food Truck"}
                      "l1" {:location-id "l1" :name "Alice's Bistro"}
                      "l3" {:location-id "l3" :name "Carol's Pop-Up"}}
          s (store/mem-store locations)
          all-l (store/all-locations s)]
      (is (= 3 (count all-l)))
      (is (= "l1" (:location-id (first all-l))))
      (is (= "l3" (:location-id (last all-l)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-service-record :location-id "l1" :value {:orders 42}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-locations
  (testing "MemStore with-locations replaces the location directory"
    (let [s (store/mem-store {})
          new-locations {"l1" {:location-id "l1" :name "Alice's Bistro"}}]
      (is (= 0 (count (store/all-locations s))))
      (store/with-locations s new-locations)
      (is (= 1 (count (store/all-locations s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo locations"
    (let [s (store/seed-db)]
      (is (> (count (store/all-locations s)) 0))
      (is (some? (store/location s "location-1")))
      (is (some? (store/location s "location-2")))
      (is (some? (store/location s "location-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for location-id"
    (let [demo (store/demo-data)
          locations (:locations demo)]
      (doseq [[k v] locations]
        (is (string? k) "keys must be strings")
        (is (string? (:location-id v)) "location-id must be string")
        (is (= k (:location-id v)) "key must match location-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
