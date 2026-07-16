(ns restaurantops.governor-test
  "Pure unit tests of `restaurantops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [restaurantops.advisor :as adv]
            [restaurantops.governor :as gov]
            [restaurantops.store :as store]))

(def location-1 {:location-id "location-1" :name "Riverside Bistro" :registered? true :verified? true})
(def location-3 {:location-id "location-3" :name "Downtown Pop-Up Kitchen" :registered? true :verified? false})

(defn- clean-proposal [op location-id]
  {:op op :location-id location-id :summary "s" :rationale "routine restaurant coordination"
   :cites [location-id] :effect :propose :value {} :confidence 0.85})

(deftest location-unregistered-is-hard
  (testing "no location record at all -> HARD hold"
    (let [s (store/mem-store {"location-1" location-1})
          verdict (gov/check {} nil (clean-proposal :log-service-record "unknown-location") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:location-unverified} (map :rule (:violations verdict)))))))

(deftest location-unverified-is-hard
  (testing "location registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"location-3" location-3})
          verdict (gov/check {} nil (clean-proposal :log-service-record "location-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:location-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"location-1" location-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-staffing-operation "location-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"location-1" location-1})
          verdict (gov/check {} nil (clean-proposal :finalize-food-safety-clearance "location-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest food-safety-clearance-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches finalizing a food-safety-clearance decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"location-1" location-1})
          poisoned (assoc (clean-proposal :log-service-record "location-1")
                          :rationale "finalized the food safety clearance decision for this line"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest allergen-exclusion-override-content-is-hard
  (testing "a proposal touching an allergen-exclusion-requirement override is HARD-blocked, same as clearance"
    (let [s (store/mem-store {"location-1" location-1})
          poisoned (assoc (clean-proposal :log-service-record "location-1")
                          :rationale "decided to override allergen exclusion requirement for table 9"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest kitchen-equipment-direct-control-content-is-hard
  (testing "a proposal touching direct kitchen-equipment actuation is HARD-blocked"
    (let [s (store/mem-store {"location-1" location-1})
          poisoned (assoc (clean-proposal :schedule-staffing-operation "location-1")
                          :summary "actuate equipment: turn off the walk-in cooler remotely")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest health-authority-content-is-hard
  (testing "a proposal touching health-department/inspection-clearance/license enforcement is HARD-blocked"
    (let [s (store/mem-store {"location-1" location-1})
          poisoned (assoc (clean-proposal :coordinate-supply-order "location-1")
                          :summary "contact health department for inspection clearance and license suspension review")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-food-safety-concern-is-not-scope-excluded
  (testing "flagging observed allergen-mismatch/temperature-abuse concerns as a FOOD SAFETY CONCERN (not a clearance decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"location-1" location-1})
          concern (assoc (clean-proposal :flag-food-safety-concern "location-1")
                         :value {:concern "table 7 sesame allergen mismatch, walk-in cooler observed at 49F for 2 hours"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (allergen/temperature) is exactly what this op exists to surface"))))

(deftest food-safety-concern-always-escalates-clean
  (testing ":flag-food-safety-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"location-1" location-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-food-safety-concern "location-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a :coordinate-supply-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"location-1" location-1})
          expensive (assoc (clean-proposal :coordinate-supply-order "location-1")
                           :value {:item "walk-in cooler repair" :estimated-cost 5000.0}
                           :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-order-does-not-force-escalate
  (testing "a :coordinate-supply-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"location-1" location-1})
          cheap (assoc (clean-proposal :coordinate-supply-order "location-1")
                       :value {:item "disposable takeout containers" :estimated-cost 130.0}
                       :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-trip regression -----------------------------
;;
;; A known bug class in this actor fleet: the governor's own
;; scope-exclusion term list is sometimes phrased as a bare noun (e.g.
;; "safety"), which then accidentally matches inside the mock advisor's
;; own DEFAULT rationale/disclaimer text for a legitimate, allowed
;; proposal -- causing the actor to self-block its own happy path. This
;; is a dedicated regression test: every op the default mock advisor can
;; generate, with default (non-`out-of-scope?`) request patches, must
;; NEVER trip `:scope-excluded`, and every op except
;; `:flag-food-safety-concern` must NEVER be forced into `:escalate` by
;; `:high-stakes?` at default confidence/value.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals for every allowed op never trip the governor's scope-exclusion check"
    (let [s (store/mem-store {"location-1" location-1})]
      (doseq [op [:log-service-record :schedule-staffing-operation :coordinate-supply-order
                  :flag-food-safety-concern]]
        (let [proposal (adv/infer nil {:op op :location-id "location-1" :patch {}})
              verdict (gov/check {:location-id "location-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:summary :rationale]))))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must always be inside the closed op allowlist")))))))
