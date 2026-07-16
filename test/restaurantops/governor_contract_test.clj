(ns restaurantops.governor-contract-test
  "Integration tests: full OperationActor graph exercising the governor's
  hard checks, escalation logic, and audit trail."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [restaurantops.advisor :as advisor]
            [restaurantops.store :as store]
            [restaurantops.operation :as op]))

(defn exec-request [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn resume-approval [actor tid status]
  (g/run* actor {:approval {:status status :by "coordinator"}} {:thread-id tid :resume? true}))

(deftest service-record-logging-full-flow
  (testing "clean service-record proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1" :phase 3}
          result (exec-request actor "t1"
                               {:op :log-service-record :location-id "location-1" :patch {:orders 42 :menu-items ["ramen"]}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/ledger db)) 0)
          "commit must append audit facts to ledger")
      (is (> (count (store/coordination-log db)) 0)
          "commit must append record to coordination-log"))))

(deftest food-safety-concern-always-escalates
  (testing ":flag-food-safety-concern escalates for human approval, regardless of phase/confidence"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2" :phase 3}
          result (exec-request actor "t2"
                               {:op :flag-food-safety-concern :location-id "location-1"
                                :patch {:concern "allergen mismatch" :confidence 0.99}}
                               ctx)]
      (is (some? result))
      ;; At this point the actor is paused for approval, not yet committed
      (is (= 0 (count (store/coordination-log db)))
          "food-safety concern must not auto-commit, must wait for approval")
      ;; Now approve it
      (resume-approval actor "t2" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, record must be committed"))))

(deftest high-cost-supply-order-always-escalates
  (testing "a high-cost :coordinate-supply-order escalates for human approval, even at phase 3 clean"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2b" :phase 3}
          result (exec-request actor "t2b"
                               {:op :coordinate-supply-order :location-id "location-1"
                                :patch {:item "walk-in cooler repair" :quantity 1 :estimated-cost 2600.0}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "high-cost supply order must not auto-commit, must wait for approval")
      (resume-approval actor "t2b" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, record must be committed"))))

(deftest low-cost-supply-order-auto-commits
  (testing "a low-cost :coordinate-supply-order auto-commits at phase 3 when clean"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2c" :phase 3}
          result (exec-request actor "t2c"
                               {:op :coordinate-supply-order :location-id "location-1"
                                :patch {:item "disposable takeout containers" :quantity 500 :estimated-cost 130.0}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/coordination-log db)) 0)
          "low-cost supply order must auto-commit when clean at phase 3"))))

(deftest unregistered-location-hard-hold
  (testing "unregistered location -> permanent HARD hold, never escalates"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-3" :phase 3}]
      (exec-request actor "t3"
                     {:op :log-service-record :location-id "unknown-location"
                      :patch {:orders 0}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "HARD hold must never commit"))))

(deftest unverified-location-hard-hold
  (testing "registered but unverified location -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-4" :phase 3}
          result (exec-request actor "t4"
                               {:op :log-service-record :location-id "location-3"
                                :patch {:orders 10}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "unverified location must HARD hold"))))

(deftest effect-not-propose-hard-hold
  (testing "proposal with :effect :commit (not :propose) -> hard hold"
    (let [db (store/seed-db)
          bad-advisor (reify advisor/Advisor
                        (-advise [_ _ req]
                          (assoc (advisor/infer nil req) :effect :commit)))
          actor (op/build db {:advisor bad-advisor})
          ctx {:actor-id "test-5" :phase 3}
          result (exec-request actor "t5"
                               {:op :log-service-record :location-id "location-1"
                                :patch {:orders 42}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "non-:propose effect must HARD hold"))))

(deftest scope-excluded-content-hard-hold
  (testing "proposal drifting into food-safety-clearance/allergen-override scope -> permanent hard hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-6" :phase 3}
          result (exec-request actor "t6"
                               {:op :log-service-record :location-id "location-1"
                                :out-of-scope? true  ; triggers scope pollution in advisor
                                :patch {}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "scope-excluded content must HARD hold"))))

(deftest phase-1-approval-gate
  (testing "phase 1 approved request -> commits after human approval"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-7" :phase 1}]
      (exec-request actor "t7"
                     {:op :log-service-record :location-id "location-1"
                      :patch {:orders 42}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "phase 1 must not auto-commit, requires approval")
      (resume-approval actor "t7" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, must commit")
      (is (some #(= :committed (:t %)) (store/ledger db))
          "committed fact must be logged after approval"))))

(deftest audit-trail-completeness
  (testing "every decision leaves immutable audit facts"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-8" :phase 3}]
      (exec-request actor "t8a"
                     {:op :log-service-record :location-id "location-1" :patch {:orders 42}}
                     ctx)
      (exec-request actor "t8b"
                     {:op :log-service-record :location-id "unknown" :patch {:orders 42}}
                     ctx)
      (let [ledger (store/ledger db)]
        (is (> (count ledger) 0))
        (is (some #(= :committed (:t %)) ledger)
            "successful commits must be logged")
        (is (some #(= :governor-hold (:t %)) ledger)
            "HARD holds must be logged")))))
