(ns restaurantops.governor
  "RestaurantGovernor -- the independent compliance layer that earns the
  RestaurantAdvisor the right to commit. The advisor has no notion of
  whether a location is actually registered and health-permit-verified,
  whether its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (service-record logging, staffing-operation scheduling, supply-order
  coordination, food-safety-concern flagging). It NEVER performs or
  authorizes:
    - finalizing a food-safety-clearance decision
    - overriding an allergen-exclusion requirement
    - direct kitchen-equipment actuation/control
    - food-safety-authority enforcement (health-department clearance,
      inspection sign-off, permit/license suspension, compliance
      enforcement)

  Three HARD checks below (+ a fourth additive one, see further down),
  ALL permanent, un-overridable by any human approval:

    1. Location unverified        -- the target location (restaurant
                                     address or mobile food-service unit)
                                     record must exist AND be
                                     independently confirmed
                                     `:registered?`/`:verified?` (business
                                     registration + health permit) in the
                                     store before ANY proposal for it may
                                     commit or even escalate. Never trusts
                                     a proposal's own claim about the
                                     location -- re-derived from the
                                     location's own store record, the same
                                     'ground truth, not self-report'
                                     discipline every sibling actor's
                                     governor uses.
    2. Effect not :propose        -- every proposal's `:effect` MUST be
                                     `:propose`. Any other effect value
                                     is, by construction, a claim to
                                     directly actuate/commit outside
                                     governance -- HARD block, not merely
                                     low-confidence.
    3. Scope exclusion            -- ANY proposal (regardless of op)
                                     whose op, rationale, summary,
                                     citations or draft value touches
                                     food-safety-clearance-finalization/
                                     allergen-exclusion-override/
                                     kitchen-equipment-actuation/
                                     food-safety-authority territory is a
                                     HARD, PERMANENT block -- this actor's
                                     charter excludes that territory
                                     structurally, not as a rollout
                                     milestone. Evaluated UNCONDITIONALLY
                                     on every proposal. An op outside the
                                     closed four-op allowlist is the SAME
                                     failure mode (an advisor proposing
                                     something it was never authorized to
                                     propose) and is folded into this same
                                     check. `:flag-food-safety-concern`
                                     itself is never excluded by this
                                     check -- surfacing a concern for a
                                     human is exactly this actor's job;
                                     only FINALIZING/overriding/actuating
                                     is excluded (see `scope-excluded-terms`
                                     below -- phrased as the
                                     finalization/execution ACTION, never
                                     a bare noun like 'safety', so the
                                     default mock advisor's own
                                     `:flag-food-safety-concern` rationale
                                     never self-trips this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-food-safety-concern` -- ALWAYS escalates to a
      human, regardless of confidence, regardless of how clean the
      proposal otherwise is. `restaurantops.phase` independently agrees:
      `:flag-food-safety-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      procurement proposal always needs a human sign-off, even when the
      governor and phase would otherwise allow auto-commit.

  A fourth HARD check, additive, same permanence tier as the three above:

    4. Cross-actor handoff cold-chain incompatibility -- when a
       `:log-supply-receipt` (or any other) proposal's `:value` carries
       BOTH a `:handoff` record (the superproject ADR-2800000500 wire
       shape an upstream cold-chain 3PL such as cloud-itonami-jsic-4721
       populates on ITS OWN `:log-outbound-shipment` -- same field names
       as that repo's own ADR-2607177600 `:handoff`, no shared code, no
       shared store) AND a `:storage-unit-id` naming which of THIS
       location's own cold-storage units (`cold-storage-requirements`
       below) the delivery is being placed into, this actor independently
       verifies the handoff's declared cold-chain-temp-min-c/max-c window
       overlaps that storage unit's own operating band -- catching a
       temperature-tier mismatch (e.g. a frozen delivery accepted into a
       walk-in refrigerator, not a freezer) before it reaches this
       actor's own store. Optional on both fields, same asymmetric
       discipline every cross-actor reference in this fleet uses: a
       proposal missing either field is never held on this basis."
  (:require [clojure.string :as str]
            [restaurantops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example single-location restaurant procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-supply-order` proposal citing an
  `:estimated-cost` above this value ALWAYS escalates to human sign-off,
  regardless of confidence or rollout phase."
  500.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`)."
  #{:log-service-record :schedule-staffing-operation
    :coordinate-supply-order :log-supply-receipt :flag-food-safety-concern})

;; ────── Cross-Actor Handoff Receipt (jsic-4721 -> isic-5610) ──────
;;
;; This location's OWN cold-storage-unit temperature reference bands --
;; independent reference data, no shared code/store with
;; cloud-itonami-jsic-4721 (see `cold-chain-handoff-violations` below).
;; Deliberately narrow, domain-illustrative to this restaurant's own
;; kitchen equipment, not a shared shape with jsic-4721's own
;; `coldchain.facts/commodity-classes` (a different actor's different
;; equipment).
(def cold-storage-requirements
  "storage-unit-id -> {:storage-temp-min-c .. :storage-temp-max-c ..}."
  {:walk-in-refrigerator {:storage-temp-min-c 0.0 :storage-temp-max-c 4.0}
   :freezer {:storage-temp-min-c -25.0 :storage-temp-max-c -15.0}})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-food-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- finalizing a food-safety-
  clearance decision, overriding an allergen-exclusion requirement,
  directly actuating kitchen equipment, or food-safety-authority
  enforcement. Scanned across the proposal's op/summary/rationale/cites/
  value, never trusting the advisor's own framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'finalize the food safety clearance'), never a bare noun
  like 'safety' or 'concern' -- a bare noun would accidentally match
  inside this actor's own legitimate `:flag-food-safety-concern` default
  proposal text (whose whole job is to talk about food-safety concerns)
  and self-block the happy path. See
  `restaurantops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["food safety clearance" "food-safety-clearance" "finalize clearance"
   "finalized clearance" "clearance decision" "food safety approval"
   "食品安全認可の確定" "食品安全認可を確定" "衛生証明書の発行" "衛生証明の発行"
   "override allergen" "overrides allergen" "overrode allergen"
   "allergen exclusion override" "allergen-exclusion override"
   "waive allergen" "bypass allergen" "ignore allergen" "allergen override"
   "アレルゲン除外" "アレルゲン上書き" "アレルゲン無視"
   "kitchen equipment override" "direct equipment control"
   "actuate equipment" "equipment override" "shut down equipment"
   "power off equipment" "control the oven" "control the fryer" "control the grill"
   "health department" "health inspector" "inspection clearance"
   "regulatory clearance" "compliance sign-off" "license suspension"
   "license-suspension" "permit revocation" "permit-revocation"
   "保健所" "衛生当局" "営業許可取消" "違反"])

;; ----------------------------- checks -----------------------------

(defn- location-unverified-violations
  "The target location (restaurant address or mobile food-service unit)
  must exist AND be independently `:registered?`/`:verified?` (business
  registration + health permit) in the store -- never trust the
  proposal's own `:location-id` claim without a store lookup."
  [{:keys [location-id]} st]
  (let [l (store/location st location-id)]
    (when-not (and l (:registered? l) (:verified? l))
      [{:rule :location-unverified
        :detail (str location-id " は未登録または未検証の店舗/拠点(営業許可) -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches food-safety-clearance-finalization/allergen-
  exclusion-override/kitchen-equipment-actuation/food-safety-authority
  territory, regardless of confidence or how clean every other check is.
  Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "食品安全認可の確定/アレルゲン除外要件の上書き/厨房設備の直接操作/食品安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn- handoff-window-overlaps-storage-unit?
  "Positive-sense convenience predicate: does the declared handoff's
  cold-chain-temp-min-c/max-c window OVERLAP `unit`'s own
  storage-temp-min-c/max-c band at all? Mirrors cloud-itonami-jsic-4721's
  own `coldchain.facts/handoff-compatible-with-commodity-class?` overlap
  reasoning (not a strict subset in either direction -- a storage unit
  describes a whole equipment's operating band, not one delivery's
  declared safety margin), but this is an independent implementation
  with no shared code."
  [handoff-min-c handoff-max-c unit]
  (boolean
   (and (some? unit)
        (some? handoff-min-c)
        (some? handoff-max-c)
        (<= handoff-min-c handoff-max-c)
        (<= handoff-min-c (:storage-temp-max-c unit))
        (<= (:storage-temp-min-c unit) handoff-max-c))))

(defn- cold-chain-handoff-violations
  "HARD, additive: when a proposal's `:value` carries BOTH a `:handoff`
  record and a `:storage-unit-id`, independently verify the handoff's
  declared cold-chain-temp-min-c/max-c window overlaps that storage
  unit's own reference band (`cold-storage-requirements`). Optional on
  both fields -- a proposal missing either is never held on this basis
  (same asymmetric discipline as every cross-actor reference check in
  this fleet, e.g. cloud-itonami-jsic-4721's own `lot-physical-
  violations`)."
  [proposal]
  (let [handoff (get-in proposal [:value :handoff])
        unit-id (get-in proposal [:value :storage-unit-id])
        unit (get cold-storage-requirements unit-id)
        handoff-min (:handoff/cold-chain-temp-min-c handoff)
        handoff-max (:handoff/cold-chain-temp-max-c handoff)]
    (when (and (map? handoff) (some? unit) (some? handoff-min) (some? handoff-max)
               (not (handoff-window-overlaps-storage-unit? handoff-min handoff-max unit)))
      [{:rule :handoff-cold-chain-window-incompatible-with-storage-unit
        :detail (str "受領handoffの宣言コールドチェーン窓(" handoff-min "℃~" handoff-max
                      "℃)が割り当てられた保管ユニット(" (pr-str unit-id) ")の運用帯と重ならない -- 温度帯不整合")}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost` above
  `supply-cost-threshold` -- always needs human sign-off (SOFT escalate,
  not a hard block: the order itself is in scope, only its size requires
  a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a RestaurantAdvisor proposal against the governor rules. Returns
  {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
  :hard? bool}."
  [request _context proposal store]
  (let [location-id (or (:location-id proposal) (:location-id request))
        hard (into []
                   (concat (location-unverified-violations {:location-id location-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)
                           (cold-chain-handoff-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :location-id (:location-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
