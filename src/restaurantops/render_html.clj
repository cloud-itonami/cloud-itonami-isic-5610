(ns restaurantops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had no demo page and no generator at all. This
  namespace drives the REAL actor stack (`restaurantops.operation` ->
  `restaurantops.governor` -> `restaurantops.store`) through a scenario
  adapted from this repo's own `restaurantops.sim` demo driver
  (`clojure -M:dev:run`, run BEFORE this file was written to confirm the
  real seeded location ids `location-1`/`location-2`/`location-3` and the
  real request shape `{:op .. :location-id .. :patch {..}}`), extended
  with the two governor checks the sim does NOT cover (the cross-actor
  cold-chain handoff mismatch and the closed-op-allowlist violation) so
  that every one of the governor's five HARD rules actually fires.

  Nothing on the page is hand-typed domain content:

    - every location id/name/registration flag comes from
      `restaurantops.store/demo-data` via `store/all-locations`
    - every rollout phase, its label, its `:writes` and its `:auto` set
      come from `restaurantops.phase/phases`
    - the op allowlist, the always-escalate set, the confidence floor,
      the supply-cost threshold and the cold-storage temperature bands
      come from the corresponding `restaurantops.governor` vars
    - every hold, its rule, its detail string and its confidence come
      from `governor/hold-fact` output sitting on the store ledger
    - every committed record comes from `store/coordination-log`

  Deterministic by construction: no timestamps, no randomness, and every
  map is canonicalised into a key-sorted form before printing, so page
  bytes cannot depend on hash-map iteration order. Two consecutive runs
  are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [restaurantops.advisor :as advisor]
            [restaurantops.governor :as governor]
            [restaurantops.operation :as op]
            [restaurantops.phase :as phase]
            [restaurantops.store :as store]))

;; ----------------------------- scenario -----------------------------

(def ^:private coordinator-phase-1
  {:actor-id "coord-1" :actor-role :restaurant-coordinator :phase 1})

(def ^:private coordinator-phase-3
  {:actor-id "coord-1" :actor-role :restaurant-coordinator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "restaurant-coordinator-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition this actor can produce.

  Commits: a phase-1 service-record log (enabled but not auto-eligible at
  phase 1 -> escalates with `:phase-approval`, human approves); the same
  op at phase 3 on the food truck (governor-clean, high confidence ->
  auto-commits); a staffing-operation schedule and a low-cost supply
  order (both auto-commit at phase 3); a high-cost supply order (above
  `governor/supply-cost-threshold` -> ALWAYS escalates even at phase 3,
  human approves); a food-safety-concern flag (never a member of any
  phase's `:auto` set, and in `governor/always-escalate-ops` -> always
  escalates, human approves); and a supply receipt carrying a
  cross-actor cold-chain `:handoff` whose declared window overlaps the
  freezer band it is placed into.

  HARD holds -- one per governor rule, none of which a human can
  override:
    :handoff-cold-chain-window-incompatible-with-storage-unit
      a frozen delivery placed into the walk-in refrigerator
    :location-unverified   (twice) an unregistered location, and
      `location-3`, which is registered but not health-permit verified
    :effect-not-propose    an advisor that claims a direct actuation
    :scope-excluded        an advisor that drifts into
      food-safety-clearance-finalization / allergen-exclusion-override
    :op-not-allowed        an advisor that proposes an op outside the
      closed allowlist

  Returns the store. Every field the renderer reads is real governor /
  store output."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        ;; A compromised advisor claiming a DIRECT actuation instead of a
        ;; proposal -- same injection this repo's own `sim` uses.
        actuating-actor
        (op/build db {:advisor (reify advisor/Advisor
                                 (-advise [_ _ req]
                                   (assoc (advisor/infer nil req) :effect :commit)))})
        ;; A compromised advisor drafting a well-formed proposal but
        ;; labelling it with an op outside `governor/allowed-ops`.
        off-allowlist-actor
        (op/build db {:advisor (reify advisor/Advisor
                                 (-advise [_ _ req]
                                   (assoc (advisor/infer nil (assoc req :op :log-service-record))
                                          :op (:op req))))})]

    ;; --- commits -----------------------------------------------------
    (exec! actor "c1" {:op :log-service-record :location-id "location-1"
                       :patch {:orders 42 :table-turns 12
                               :menu-items ["ramen" "gyoza"]
                               :allergen-flags ["sesame"]}}
           coordinator-phase-1)
    (approve! actor "c1")

    (exec! actor "c2" {:op :log-service-record :location-id "location-2"
                       :patch {:orders 30 :table-turns 9
                               :menu-items ["salad"] :allergen-flags []}}
           coordinator-phase-3)

    (exec! actor "c3" {:op :schedule-staffing-operation :location-id "location-2"
                       :patch {:shift "dinner-prep" :date "2026-07-20"
                               :window "16:00-17:30"}}
           coordinator-phase-3)

    (exec! actor "c4" {:op :coordinate-supply-order :location-id "location-1"
                       :patch {:item "disposable takeout containers"
                               :quantity 500 :estimated-cost 130.0}}
           coordinator-phase-3)

    (exec! actor "c5" {:op :coordinate-supply-order :location-id "location-1"
                       :patch {:item "walk-in cooler repair parts"
                               :quantity 1 :estimated-cost 2600.0}}
           coordinator-phase-3)
    (approve! actor "c5")

    (exec! actor "c6" {:op :flag-food-safety-concern :location-id "location-2"
                       :patch {:concern "walk-in cooler at 49F for 2h"
                               :confidence 0.92}}
           coordinator-phase-3)
    (approve! actor "c6")

    ;; Cold-chain handoff that IS compatible: a frozen delivery placed
    ;; into the freezer, whose declared window overlaps that unit's own
    ;; band in `governor/cold-storage-requirements`.
    (exec! actor "c7" {:op :log-supply-receipt :location-id "location-1"
                       :patch {:handoff {:handoff/cold-chain-temp-min-c -22.0
                                         :handoff/cold-chain-temp-max-c -18.0}
                               :storage-unit-id :freezer}}
           coordinator-phase-3)

    ;; --- HARD holds --------------------------------------------------
    ;; Same frozen window, but placed into the walk-in refrigerator.
    (exec! actor "h1" {:op :log-supply-receipt :location-id "location-2"
                       :patch {:handoff {:handoff/cold-chain-temp-min-c -22.0
                                         :handoff/cold-chain-temp-max-c -18.0}
                               :storage-unit-id :walk-in-refrigerator}}
           coordinator-phase-3)

    (exec! actor "h2" {:op :log-service-record :location-id "location-99"
                       :patch {:orders 0}}
           coordinator-phase-3)

    (exec! actor "h3" {:op :log-service-record :location-id "location-3"
                       :patch {:orders 10}}
           coordinator-phase-3)

    (exec! actuating-actor "h4" {:op :schedule-staffing-operation :location-id "location-1"
                                 :patch {:shift "lunch-prep" :date "2026-07-22"}}
           coordinator-phase-3)

    (exec! actor "h5" {:op :log-service-record :location-id "location-1"
                       :out-of-scope? true :patch {}}
           coordinator-phase-3)

    (exec! off-allowlist-actor "h6" {:op :finalize-food-safety-clearance
                                     :location-id "location-1" :patch {}}
           coordinator-phase-3)
    db))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- canon
  "Recursively re-key every map into a key-sorted map so `pr-str` output
  cannot depend on hash-map iteration order. The one structural guard
  that makes the page byte-stable independent of collection internals."
  [v]
  (cond
    (map? v) (into (sorted-map-by #(compare (str %1) (str %2)))
                   (map (fn [[k x]] [k (canon x)]) v))
    (set? v) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) (map canon v))
    (vector? v) (mapv canon v)
    (seq? v) (mapv canon v)
    :else v))

(defn- kw-str [k] (if (keyword? k) (name k) (str k)))

(defn- kw-list
  "`#{:a :b}` -> `\"a, b\"`, name-sorted -- set iteration order never
  reaches the page."
  [kws]
  (if (seq kws) (str/join ", " (sort (map kw-str kws))) "—"))

(defn- value-cell
  "The drafted payload of a committed record, minus the `:location-id`
  it is already filed under, printed key-sorted."
  [value]
  (let [pairs (dissoc value :location-id)]
    (if (empty? pairs)
      "<span class=\"muted\">—</span>"
      (str "<code>"
           (esc (str/join "  " (map (fn [[k v]] (str (kw-str k) "=" (pr-str v)))
                                    (canon pairs))))
           "</code>"))))

;; ----------------------------- sections -----------------------------

(defn- hold? [f] (= :governor-hold (:t f)))

(defn- last-fact-for [ledger location-id]
  (last (filter #(= (:location-id %) location-id) ledger)))

(defn- status-cell [ledger location-id]
  (let [f (last-fact-for ledger location-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (hold? f) (str "<span class=\"critical\">HARD hold &middot; "
                     (esc (kw-str (or (first (:basis f)) :unknown))) "</span>")
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"warn\">rejected by approver</span>"
      :else (str "<span class=\"muted\">" (esc (kw-str (:t f))) "</span>"))))

(defn- location-row [ledger {:keys [location-id name registered? verified?]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc location-id) (esc name)
          (if registered? "<span class=\"ok\">registered</span>"
              "<span class=\"critical\">not registered</span>")
          (if verified? "<span class=\"ok\">health-permit verified</span>"
              "<span class=\"critical\">not verified</span>")
          (status-cell ledger location-id)))

(defn- phase-row [[n {:keys [label writes auto]}]]
  (format "        <tr><td class=\"num\">%s</td><td>%s%s</td><td>%s</td><td>%s</td></tr>"
          n (esc label)
          (if (= n phase/default-phase) " <span class=\"badge\">default</span>" "")
          (esc (kw-list writes)) (esc (kw-list auto))))

(defn- op-gate-row
  "One row of the closed-op contract, derived from the governor's and the
  phase gate's own vars -- not a hand-written description."
  [op-kw]
  (let [auto3 (get-in phase/phases [phase/default-phase :auto])
        always? (contains? governor/always-escalate-ops op-kw)
        auto? (contains? auto3 op-kw)]
    (format "        <tr><td><code>:%s</code></td><td>%s</td><td>%s</td></tr>"
            (esc (kw-str op-kw))
            (cond
              always? "<span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span>"
              auto? (format "<span class=\"ok\">auto-commit at phase %s when governor-clean</span>" phase/default-phase)
              :else "<span class=\"warn\">human approval</span>")
            (if always?
              "<span class=\"muted\">in <code>governor/always-escalate-ops</code></span>"
              (format "<span class=\"muted\">in phase-%s <code>:auto</code>: %s</span>"
                      phase/default-phase (if auto? "yes" "no"))))))

(defn- storage-row [[unit-id {:keys [storage-temp-min-c storage-temp-max-c]}]]
  (format "        <tr><td><code>:%s</code></td><td class=\"num\">%s</td><td class=\"num\">%s</td></tr>"
          (esc (kw-str unit-id)) storage-temp-min-c storage-temp-max-c))

(defn- hold-row [{:keys [op location-id violations confidence]}]
  (format "        <tr><td><code>:%s</code></td><td><code>%s</code></td><td><span class=\"critical\">%s</span></td><td>%s</td><td class=\"num\">%s</td></tr>"
          (esc (kw-str op)) (esc location-id)
          (esc (str/join ", " (map (comp kw-str :rule) violations)))
          (esc (str/join " / " (map :detail violations)))
          confidence))

(defn- committed-row [{:keys [op location-id value payload]}]
  (format "        <tr><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str op)) (esc location-id) (value-cell value)
          (if-let [by (:approved-by payload)]
            (str "<span class=\"warn\">approved by " (esc by) "</span>")
            "<span class=\"ok\">auto-committed</span>")))

(defn- ledger-row [{:keys [t op location-id basis summary]}]
  (format "        <tr><td>%s</td><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (if (= :governor-hold t)
            (str "<span class=\"critical\">" (esc (kw-str t)) "</span>")
            (str "<span class=\"ok\">" (esc (kw-str t)) "</span>"))
          (esc (kw-str op)) (esc location-id)
          (esc (str/join ", " (map kw-str basis)))
          (esc (or summary ""))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders operator-console.html from a store `db` that has already run
  `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        holds (filterv hold? ledger)
        locations (store/all-locations db)
        committed (vec (store/coordination-log db))]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-5610 &middot; restaurants &amp; mobile food service</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Restaurants &amp; mobile food service (ISIC 5610) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · coordination only · governor-gated</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Locations</h2>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>restaurantops.store</code> via <code>restaurantops.render-html</code> (<code>clojure -M:dev:render-html</code>). A location must be independently <code>:registered?</code> AND <code>:verified?</code> (business registration + health permit) in the store before any proposal for it may commit <em>or even escalate</em> — never taken from the proposal's own claim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Location</th><th>Name</th><th>Registration</th><th>Health permit</th><th>Last decision</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial location-row ledger) locations)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds in this run</h2>\n"
     "    <p class=\"muted\">A HARD hold is permanent and un-overridable — it never reaches a human approver at all. Rules and detail strings below are the governor's own output, read back off the append-only ledger.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Location</th><th>Rule</th><th>Governor detail</th><th>Advisor confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate</h2>\n"
     "    <p class=\"muted\">The closed op allowlist (<code>governor/allowed-ops</code>) crossed with the rollout phase gate (<code>phase/phases</code>). An op outside this list is a scope violation by construction. Confidence floor <span class=\"num\">"
     governor/confidence-floor
     "</span>; a <code>:coordinate-supply-order</code> citing an <code>:estimated-cost</code> above <span class=\"num\">"
     governor/supply-cost-threshold
     "</span> always escalates regardless of phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th><th>Source</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map op-gate-row (sort-by kw-str governor/allowed-ops))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout phases</h2>\n"
     "    <p class=\"muted\"><code>:flag-food-safety-concern</code> is deliberately absent from every phase's auto set, including the last — a permanent structural fact, not a rollout milestone still to come.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Label</th><th>May write</th><th>May auto-commit</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map phase-row (sort-by key phase/phases))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Cold-storage reference bands</h2>\n"
     "    <p class=\"muted\">This location's own equipment bands (<code>governor/cold-storage-requirements</code>). When an inbound supply receipt carries both an upstream cold-chain <code>:handoff</code> record and a <code>:storage-unit-id</code>, the governor independently checks the declared handoff window overlaps the unit's band — catching a frozen delivery accepted into a refrigerator before it reaches the store.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Storage unit</th><th>Min °C</th><th>Max °C</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map storage-row (sort-by (comp kw-str key) governor/cold-storage-requirements))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Committed coordination log</h2>\n"
     "    <p class=\"muted\">The SSoT writes this run produced (<code>store/coordination-log</code>). A record carrying <code>:approved-by</code> passed through a human; the rest auto-committed under the phase gate. This attribution is read back off the stored record itself — <code>MemStore</code>'s <code>commit-record!</code> persists the whole record, so the <code>:approved-by</code> the <code>:request-approval</code> node writes onto <code>:payload</code> does reach the SSoT here — not re-derived from the in-run approval message.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Location</th><th>Drafted payload</th><th>Path</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map committed-row committed)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and every hold, in the order the actor produced them. Read this section's limit honestly: only <code>:committed</code> and <code>:governor-hold</code> facts are written to the store. The graph's <code>:approval-requested</code>/<code>:approval-granted</code> facts stay in the run's in-memory <code>:audit</code> channel and never reach <code>store/ledger</code>, and <code>commit-fact</code> carries no approval field — so a <code>:committed</code> row here does <em>not</em> say whether a human approved it. That attribution exists only on the coordination-log record above.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Location</th><th>Basis</th><th>Summary</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>Generated at build time by <code>restaurantops.render-html</code> from a fresh <code>store/seed-db</code>. "
     (count ledger) " ledger facts, " (count holds) " HARD holds, " (count committed)
     " committed records. Deterministic — regenerating produces byte-identical output.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        hs (filterv hold? (store/ledger db))]
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (spit out (render db))
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count hs) "HARD holds,"
             (count (store/coordination-log db)) "committed records )")))
