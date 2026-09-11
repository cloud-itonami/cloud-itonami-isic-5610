(ns restaurantops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean service-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs the
  same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a staffing-operation-scheduling request and a
  low-cost supply-order coordination (both auto-commit clean at phase 3),
  then a high-cost supply-order (ALWAYS escalates regardless of phase),
  then a food-safety-concern flag (ALWAYS escalates, at any phase --
  approve, then commit), then HARD-hold scenarios: an unregistered
  location, a location registered but not yet verified, a proposal whose
  own `:effect` is not `:propose`, and a proposal that has drifted into
  the permanently-excluded food-safety-clearance/allergen-exclusion-
  override scope."
  (:require [langgraph.graph :as g]
            [restaurantops.advisor :as advisor]
            [restaurantops.store :as store]
            [restaurantops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "restaurant-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :restaurant-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :restaurant-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-service-record location-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-service-record :location-id "location-1"
                                  :patch {:orders 42 :table-turns 12 :menu-items ["ramen" "gyoza"] :allergen-flags ["sesame"]}} coordinator-phase-1)]
      (println r)
      (println "-- human restaurant coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-service-record location-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-service-record :location-id "location-1"
                                  :patch {:orders 30 :table-turns 9 :menu-items ["salad"] :allergen-flags []}} coordinator-phase-3))

    (println "\n== schedule-staffing-operation location-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-staffing-operation :location-id "location-1"
                                  :patch {:shift "dinner-prep" :date "2026-07-20" :window "16:00-17:30"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order location-1, low cost (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :location-id "location-1"
                                  :patch {:item "disposable takeout containers" :quantity 500 :estimated-cost 130.0}} coordinator-phase-3))

    (println "\n== coordinate-supply-order location-1, HIGH cost (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-supply-order :location-id "location-1"
                                 :patch {:item "walk-in cooler repair parts" :quantity 1 :estimated-cost 2600.0}} coordinator-phase-3)]
      (println r)
      (println "-- human restaurant coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-food-safety-concern location-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-food-safety-concern :location-id "location-1"
                                 :patch {:concern "table 7 sesame allergen mismatch and walk-in cooler at 49F for 2h" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human restaurant coordinator reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== log-service-record location-99 (unregistered location -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-service-record :location-id "location-99"
                                  :patch {:orders 0}} coordinator-phase-3))

    (println "\n== log-service-record location-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-service-record :location-id "location-3"
                                  :patch {:orders 10}} coordinator-phase-3))

    (println "\n== schedule-staffing-operation location-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :schedule-staffing-operation :location-id "location-1"
                                           :patch {:shift "lunch-prep" :date "2026-07-22"}} coordinator-phase-3)))

    (println "\n== log-service-record location-1, advisor drifts into food-safety-clearance/allergen-override scope -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :log-service-record :location-id "location-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
