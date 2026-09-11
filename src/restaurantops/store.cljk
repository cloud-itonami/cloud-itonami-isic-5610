(ns restaurantops.store
  "SSoT for the ISIC-5610 restaurants / mobile food service COORDINATION
  actor, behind a `Store` protocol so the backend is a swap, not a rewrite --
  the same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of stand-alone
  restaurants and mobile food service units (food trucks, carts, pop-up
  kitchens -- ISIC Rev.4/5 5610, distinct from institutional/contract
  food-service sites (5629) and stand-alone event catering (5621)):
  order/table-turn/menu-item logging, shift/prep staffing-operation
  scheduling, ingredient/equipment supply-order coordination, and
  food-safety-concern flagging. It never finalizes a food-safety-clearance
  decision, never overrides an allergen-exclusion requirement, and never
  directly actuates kitchen equipment -- see `restaurantops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `locations` directory keyed by `:location-id` STRING (never
  a keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt). 'Location' deliberately
  covers both a fixed restaurant address and a mobile food-service unit
  (a food truck's registered operating base), since ISIC 5610 spans both.

  A registered/verified location (business registration + health permit)
  record must exist before ANY proposal for that location may ever commit
  or escalate -- `restaurantops.governor`'s `location-unverified-violations`
  re-derives this from the location's own `:registered?`/`:verified?`
  fields, never from proposal self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which location a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by whom
  is always a query over an immutable log.")

(defprotocol Store
  (location [s location-id] "Registered location record, or nil.
    Location map: {:location-id .. :name .. :registered? bool :verified? bool}.")
  (all-locations [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-locations [s locations] "replace/seed the location directory (map location-id->location)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained location directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:locations
   {"location-1" {:location-id "location-1" :name "Riverside Bistro"
                  :registered? true :verified? true}
    "location-2" {:location-id "location-2" :name "Sunset Boulevard Food Truck"
                  :registered? true :verified? true}
    "location-3" {:location-id "location-3" :name "Downtown Pop-Up Kitchen (in intake)"
                  :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (location [_ location-id] (get-in @a [:locations location-id]))
  (all-locations [_] (sort-by :location-id (vals (:locations @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-locations [s locations] (when (seq locations) (swap! a assoc :locations locations)) s))

(defn seed-db
  "A MemStore seeded with the demo location directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `locations` map (location-id string ->
  location map) -- the primary test/dev entry point. `locations` may be
  empty (an unregistered-everywhere store)."
  [locations]
  (->MemStore (atom {:locations (or locations {}) :ledger [] :coordination-log []})))
