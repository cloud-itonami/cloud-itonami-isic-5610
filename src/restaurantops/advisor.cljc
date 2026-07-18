(ns restaurantops.advisor
  "RestaurantAdvisor -- the *contained intelligence node* for the
  ISIC-5610 restaurants / mobile food service operations-coordination
  actor.

  It drafts exactly five kinds of back-office proposal from a closed
  allowlist: service-record logging (orders/table-turns/menu-items),
  staffing-operation scheduling (shift/prep), supply-order coordination,
  supply-receipt logging (inbound deliveries, e.g. from an upstream
  cold-chain 3PL such as cloud-itonami-jsic-4721), and food-safety-concern
  flagging. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a direct actuation -- every
  proposal's `:effect` is always `:propose`. Every output is censored
  downstream by `restaurantops.governor` before anything touches the
  SSoT.

  This advisor NEVER drafts a food-safety-clearance decision, an
  allergen-exclusion-requirement override, direct kitchen-equipment
  actuation, or any other food-safety-authority action (health-department
  clearance, inspection sign-off, permit/license decisions) -- those are
  permanently out of scope for this actor, not merely un-implemented.
  `restaurantops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised or
  confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :location-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-service-record
  "Draft a service-record log entry. Pure logging of observed operations
  (orders, table-turns, menu items served, allergen flags on the ticket)
  -- never a food-safety-clearance judgement."
  [_db {:keys [location-id patch]}]
  {:op         :log-service-record
   :location-id location-id
   :summary    (str location-id " の営業記録を記録: " (pr-str (keys patch)))
   :rationale  "注文数・卓回転・提供メニューの観察記録のみ。食品安全認可の判断なし。"
   :cites      [location-id]
   :effect     :propose
   :value      (merge {:location-id location-id} patch)
   :confidence 0.93})

(defn- propose-staffing-operation
  "Draft a shift/prep staffing-operation scheduling proposal (a
  roster/calendar entry, never a direct kitchen-equipment actuation)."
  [_db {:keys [location-id patch]}]
  {:op         :schedule-staffing-operation
   :location-id location-id
   :summary    (str location-id " のシフト/仕込みオペレーション予定を提案: " (pr-str (keys patch)))
   :rationale  "シフト・仕込みスケジュールの調整提案のみ。厨房設備の直接操作は行わない。"
   :cites      [location-id]
   :effect     :propose
   :value      (merge {:location-id location-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft an ingredient/equipment procurement coordination request (food
  stock, disposable service-ware, small kitchen equipment -- never a
  finalized purchase order; a human always confirms procurement)."
  [_db {:keys [location-id patch]}]
  {:op         :coordinate-supply-order
   :location-id location-id
   :summary    (str location-id " に関連する食材/什器調達オーダーを提案: " (pr-str (keys patch)))
   :rationale  "食材・調理器具・使い捨て什器などの調達調整提案のみ。確定発注は人間が行う。"
   :cites      [location-id]
   :effect     :propose
   :value      (merge {:location-id location-id} patch)
   :confidence 0.90})

(defn- propose-supply-receipt
  "Draft a supply-receipt log entry -- pure logging of an observed inbound
  delivery (e.g. from an upstream cold-chain 3PL such as
  cloud-itonami-jsic-4721), never a food-safety-clearance judgement. May
  carry an optional cross-actor `:handoff` record (superproject
  ADR-2800000500 wire shape, same field names as
  cloud-itonami-jsic-4721's own `:handoff` -- no shared code) plus a
  `:storage-unit-id` naming which of THIS location's own cold-storage
  units (`restaurantops.governor/cold-storage-requirements`) the delivery
  is being placed into -- `restaurantops.governor`'s
  `cold-chain-handoff-violations` independently verifies the two are
  temperature-compatible."
  [_db {:keys [location-id patch]}]
  {:op         :log-supply-receipt
   :location-id location-id
   :summary    (str location-id " の入荷記録を記録: " (pr-str (keys patch)))
   :rationale  "上流サプライヤー(冷蔵倉庫3PL等)からの入荷観察記録のみ。食品安全認可の判断なし。"
   :cites      [location-id]
   :effect     :propose
   :value      (merge {:location-id location-id} patch)
   :confidence 0.92})

(defn- propose-food-safety-concern
  "Surface a food-safety concern (allergen mismatch, temperature abuse,
  suspected contamination) for HUMAN triage. This op ALWAYS escalates in
  `restaurantops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real."
  [_db {:keys [location-id patch]}]
  {:op         :flag-food-safety-concern
   :location-id location-id
   :summary    (str location-id " の食品安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "アレルゲン不一致・温度逸脱・異物混入疑い等の観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [location-id]
   :effect     :propose
   :value      (merge {:location-id location-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-service-record (propose-service-record _db request)
                   :schedule-staffing-operation (propose-staffing-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :log-supply-receipt (propose-supply-receipt _db request)
                   :flag-food-safety-concern (propose-food-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the clearance decision and applied an allergen exclusion override")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :location-id (:location-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
