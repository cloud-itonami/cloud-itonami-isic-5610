(ns restaurantops.phase-test
  "Unit tests of `restaurantops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [restaurantops.phase :as phase]))

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-service-record :schedule-staffing-operation :coordinate-supply-order
                :log-supply-receipt :flag-food-safety-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-service-record-only
  (testing "phase 1 allows service-record and supply-receipt logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-service-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :log-supply-receipt} :commit)]
      (is (= :escalate disposition)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-staffing-operation} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-service-record :schedule-staffing-operation :coordinate-supply-order
                :log-supply-receipt]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-safety ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-service-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-staffing-operation} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-order} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-supply-receipt} :commit)]
      (is (= :commit disposition)))))

(deftest food-safety-concern-holds-when-not-enabled
  (testing ":flag-food-safety-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-food-safety-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-food-safety-concern yet"))))))

(deftest food-safety-concern-escalates-when-enabled
  (testing ":flag-food-safety-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-food-safety-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate food-safety concerns regardless of governor disposition"))))

(deftest food-safety-concern-never-in-any-phase-auto-set
  (testing ":flag-food-safety-concern must never be a member of any phase's :auto set -- a permanent structural fact, not a rollout milestone"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-food-safety-concern))
          (str "phase " ph " :auto set must never contain :flag-food-safety-concern")))))

(deftest high-cost-supply-order-escalates-at-phase-3
  (testing "the governor already turned a high-cost supply order into :escalate upstream -- phase 3 must not force it back to :commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-order} :escalate)]
      (is (= :escalate disposition)))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-service-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
