# hickey -- Clojure Decomplecting Refactoring Skill

## Overview

**hickey** is a refactoring skill for Clojure code inspired by Rich Hickey’s “Simple Made Easy”.

The goal is not to make code shorter, trendier, or more familiar. The goal is to make code **simpler** in Hickey’s sense: less intertwined, less braided together, and easier to reason about over time.

> Core principle: **Simplicity = lack of entanglement.**

---

## Operating Model

The agent follows this loop:

1. Scan the code for signs of complecting.
2. Ask structured Hickey-style questions.
3. Identify the concerns that are braided together.
4. Propose a minimal refactor.
5. Explain why the new shape is simpler.
6. Call out tradeoffs and risks.

The agent should prefer small, local refactorings unless a larger redesign is clearly justified.

---

## Core Evaluation Question

> What is braided together here that could vary independently?

Everything in this skill is a variation of that question.

---

# Core Questions

## 1. Behavior vs Representation

**Question**

Can behavior be separated from data representation?

**Look for**

```clojure
(cond
  (= (:kind x) :invoice) ...
  (= (:kind x) :refund) ...
  (= (:kind x) :subscription) ...)
```

**Likely refactors**

- `defprotocol` when dispatch is based on concrete type.
- `defmulti` when dispatch is based on data values.
- A plain lookup table when the branch is really static data.

---

## 2. Type Branching vs Polymorphism

**Question**

Can type checks be replaced with protocols?

**Look for**

```clojure
(cond
  (instance? Product x) ...
  (instance? Subscription x) ...
  (instance? Bundle x) ...)
```

**Preferred refactor**

```clojure
(defprotocol Priceable
  (subtotal [x]))
```

Then implement behavior per type.

---

## 3. Pure Logic vs Effects

**Question**

Can computation be separated from side effects?

**Look for**

```clojure
(defn process-order! [db email-client order]
  (let [total (calculate-total order)]
    (save-order! db order total)
    (send-confirmation! email-client order)
    total))
```

**Refactor direction**

Separate:

- pure calculation
- persistence
- notification
- orchestration

---

## 4. Values vs Identity

**Question**

Is mutable identity being used where immutable values are enough?

**Look for**

```clojure
(def state (atom {}))

(defn add-item! [item]
  (swap! state update :items conj item))
```

**Refactor direction**

Prefer:

```clojure
(defn add-item [cart item]
  (update cart :items conj item))
```

Use atoms only at system boundaries where identity over time is actually required.

---

## 5. Time vs Logic

**Question**

Is “now” hidden inside logic?

**Look for**

```clojure
(defn expired? [token]
  (< (:expires-at token) (System/currentTimeMillis)))
```

**Refactor**

```clojure
(defn expired? [now token]
  (< (:expires-at token) now))
```

This makes the function deterministic and testable.

---

## 6. Conditionals vs Data

**Question**

Can branching be represented as data?

**Look for**

```clojure
(case currency
  :usd "$"
  :eur "€"
  :gbp "£")
```

**Refactor**

```clojure
(def currency-symbol
  {:usd "$"
   :eur "€"
   :gbp "£"})

(defn symbol-for [currency]
  (get currency-symbol currency))
```

Use data when the behavior is really a static mapping.

---

## 7. Policy vs Mechanism

**Question**

Is the code both deciding what should happen and doing it?

**Look for**

```clojure
(defn send-notification! [user event]
  (when (and (:active? user)
             (= (:tier user) :premium)
             (= (:type event) :billing))
    (email! user event)))
```

**Refactor**

```clojure
(defn should-notify? [user event]
  (and (:active? user)
       (= (:tier user) :premium)
       (= (:type event) :billing)))

(defn notify! [email-client user event]
  (email! email-client user event))
```

---

## 8. Data Shape vs Business Rules

**Question**

Is business logic coupled to raw or nested input shape?

**Look for**

```clojure
(defn eligible? [row]
  (and (= "active" (get-in row [:account :status]))
       (> (get-in row [:metrics :last_30_days :spend]) 1000)))
```

**Refactor**

Normalize first:

```clojure
(defn account-summary [row]
  {:status (get-in row [:account :status])
   :monthly-spend (get-in row [:metrics :last_30_days :spend])})

(defn eligible? [{:keys [status monthly-spend]}]
  (and (= "active" status)
       (> monthly-spend 1000)))
```

---

## 9. Control Flow vs Data Flow

**Question**

Is imperative control flow hiding a transformation?

**Look for**

```clojure
(loop [xs orders
       acc []]
  (if (empty? xs)
    acc
    (recur (rest xs)
           (conj acc (normalize-order (first xs))))))
```

**Refactor**

```clojure
(mapv normalize-order orders)
```

---

## 10. Hidden State vs Explicit Dependencies

**Question**

Does this code depend on globals, dynamic vars, or implicit services?

**Look for**

```clojure
(def db ...)

(defn find-user [id]
  (db/query db ["select * from users where id = ?" id]))
```

**Refactor**

```clojure
(defn find-user [db id]
  (db/query db ["select * from users where id = ?" id]))
```

---

# Protocol vs Multimethod Decision Tree

Use this when you see branching, type checks, or `:type`/`:kind` dispatch.

```text
1. Is dispatch based only on the concrete type of the first argument?
   ├─ Yes → Consider defprotocol.
   └─ No  → Continue.

2. Is dispatch based on a value inside data, such as :type, :event, :op, or :kind?
   ├─ Yes → Consider defmulti.
   └─ No  → Continue.

3. Does dispatch depend on multiple inputs?
   ├─ Yes → Prefer defmulti.
   └─ No  → Continue.

4. Is the branch just a static lookup?
   ├─ Yes → Use a map/table.
   └─ No  → Continue.

5. Is the branch count tiny and unlikely to grow?
   ├─ Yes → cond/case may be acceptable.
   └─ No  → Reconsider protocol, multimethod, or data-driven design.
```

## Protocols are appropriate when

- The operation is stable.
- Dispatch is type-based.
- You want fast dispatch.
- Implementations are naturally attached to concrete types.
- You want callers to know only the operation, not every possible type.

## Multimethods are appropriate when

- Dispatch is based on arbitrary data.
- Dispatch depends on more than one value.
- Dispatch rules may evolve independently.
- You want open-ended extension without defining new concrete types.

## Plain maps are appropriate when

- The branch is static data.
- There is no real behavior.
- You are mapping keys to values or functions.

---

# Refactoring Pattern Catalog

## Pattern 1: Type Branching to Protocol

### Before

```clojure
(defrecord Product [unit-price qty])
(defrecord Subscription [monthly-price months])

(defn subtotal [item]
  (cond
    (instance? Product item)
    (* (:unit-price item) (:qty item))

    (instance? Subscription item)
    (* (:monthly-price item) (:months item))

    :else
    (throw (ex-info "Unknown item type" {:item item}))))
```

### After

```clojure
(defprotocol Priceable
  (subtotal [item]))

(defrecord Product [unit-price qty])
(defrecord Subscription [monthly-price months])

(extend-type Product
  Priceable
  (subtotal [item]
    (* (:unit-price item) (:qty item))))

(extend-type Subscription
  Priceable
  (subtotal [item]
    (* (:monthly-price item) (:months item))))
```

### Why this is simpler

The caller does not need to know every possible concrete type. The operation is stable; implementations vary independently.

### Tradeoff

Protocols dispatch on type, not arbitrary data. If dispatch depends on `:kind` inside a map, prefer multimethods.

---

## Pattern 2: Data Dispatch to Multimethod

### Before

```clojure
(defn handle-event [event]
  (case (:type event)
    :user-created (create-user! event)
    :user-deleted (delete-user! event)
    :invoice-paid (mark-invoice-paid! event)))
```

### After

```clojure
(defmulti handle-event :type)

(defmethod handle-event :user-created [event]
  (create-user! event))

(defmethod handle-event :user-deleted [event]
  (delete-user! event))

(defmethod handle-event :invoice-paid [event]
  (mark-invoice-paid! event))
```

### Why this is simpler

The central dispatcher no longer has to be edited for every new event type. Event handling can grow by extension.

### Tradeoff

Multimethods are more flexible but less direct than protocols. Do not use them when a simple map would suffice.

---

## Pattern 3: Branching to Data Table

### Before

```clojure
(defn tax-rate [country]
  (case country
    :pl 0.23
    :de 0.19
    :fr 0.20
    :uk 0.20))
```

### After

```clojure
(def tax-rates
  {:pl 0.23
   :de 0.19
   :fr 0.20
   :uk 0.20})

(defn tax-rate [country]
  (get tax-rates country))
```

### Why this is simpler

The code was not behavior; it was data. Data is easier to inspect, test, extend, and load from configuration.

### Tradeoff

Do not turn complex behavior into opaque function tables unless it improves clarity.

---

## Pattern 4: Effects Out of Pure Logic

### Before

```clojure
(defn checkout! [db email-client cart]
  (let [total (reduce + (map :price (:items cart)))
        order {:items (:items cart)
               :total total}]
    (save-order! db order)
    (send-confirmation! email-client order)
    order))
```

### After

```clojure
(defn order-from-cart [cart]
  (let [total (reduce + (map :price (:items cart)))]
    {:items (:items cart)
     :total total}))

(defn persist-order! [db order]
  (save-order! db order))

(defn notify-order! [email-client order]
  (send-confirmation! email-client order))

(defn checkout! [db email-client cart]
  (let [order (order-from-cart cart)]
    (persist-order! db order)
    (notify-order! email-client order)
    order))
```

### Why this is simpler

The order calculation can be reasoned about independently from persistence and email delivery.

### Tradeoff

There are more named functions. This is acceptable if they separate genuinely independent concerns.

---

## Pattern 5: Hidden Time to Explicit Time

### Before

```clojure
(defn trial-expired? [account]
  (> (System/currentTimeMillis)
     (:trial-ends-at account)))
```

### After

```clojure
(defn trial-expired? [now account]
  (> now (:trial-ends-at account)))
```

### Why this is simpler

The function is deterministic. Tests do not need clock mocking.

### Tradeoff

Callers must provide `now`, usually at the boundary of the system.

---

## Pattern 6: Mutable Accumulation to Value Transformation

### Before

```clojure
(defn collect-active [users]
  (let [result (atom [])]
    (doseq [u users]
      (when (:active? u)
        (swap! result conj u)))
    @result))
```

### After

```clojure
(defn collect-active [users]
  (filterv :active? users))
```

### Why this is simpler

The result is expressed as a transformation over values, not as a sequence of mutations.

### Tradeoff

For performance-sensitive paths, check allocation behavior. Prefer transducers when needed.

---

## Pattern 7: Raw Data Shape to Domain Shape

### Before

```clojure
(defn high-value-customer? [row]
  (and (= "ACTIVE" (get-in row [:customer :state]))
       (> (get-in row [:stats :revenue_cents]) 100000)))
```

### After

```clojure
(defn customer-facts [row]
  {:active? (= "ACTIVE" (get-in row [:customer :state]))
   :revenue-cents (get-in row [:stats :revenue_cents])})

(defn high-value-customer? [{:keys [active? revenue-cents]}]
  (and active?
       (> revenue-cents 100000)))
```

### Why this is simpler

Business rules no longer depend on external/raw data shape.

### Tradeoff

Normalization creates another layer. It is worth it when the raw shape is unstable or noisy.

---

# Agent Checklist

When reviewing Clojure code, the agent should inspect:

## Dispatch

- Are there `cond`, `case`, or `condp` blocks that branch by type or kind?
- Are there repeated `instance?` checks?
- Are there repeated checks on `:type`, `:kind`, `:event`, `:op`, or `:status`?
- Would protocols, multimethods, or data tables reduce central coordination?

## Effects

- Does a function calculate and perform I/O?
- Does it call functions ending in `!` from inside pure-looking logic?
- Are DB, HTTP, file, email, queue, or logging effects mixed into business rules?

## State

- Are `atom`, `ref`, `agent`, `volatile!`, `swap!`, or `reset!` used?
- Is the mutable state local and necessary?
- Could a pure value transformation replace it?

## Time and Context

- Are `System/currentTimeMillis`, `java.time.Instant/now`, `rand`, or UUID generation hidden inside logic?
- Can these be supplied as arguments?
- Is config pulled globally instead of passed explicitly?

## Data Shape

- Are business rules full of `get-in`?
- Is external naming leaking into domain logic?
- Is there a normalization boundary?

## Control Flow

- Are loops manually building collections?
- Could `map`, `filter`, `reduce`, `group-by`, `update`, `assoc`, or transducers express the transformation?
- Is ordering significant or accidental?

## Abstraction Quality

- Does the abstraction remove entanglement?
- Or does it merely hide code behind a generic name?
- Is the new abstraction stable enough to deserve a name?

---

# Severity Scoring

Use this scoring system to prioritize findings.

## Severity 1 — Cosmetic

The code could be cleaner, but no meaningful entanglement exists.

Examples:

- Slightly verbose transformation
- Minor naming issue
- Small `case` with stable options

Recommendation: mention only if useful.

---

## Severity 2 — Local Complecting

Two concerns are intertwined within one function, but the blast radius is small.

Examples:

- Pure logic mixed with a logging call
- Simple mutable accumulation
- Small branch that could be a map

Recommendation: suggest local refactor.

---

## Severity 3 — Structural Complecting

A function or namespace acts as a coordination hub for things that vary independently.

Examples:

- Central `cond` dispatch for many types
- Business logic coupled to raw database rows
- Multiple effects mixed with decision logic

Recommendation: propose protocols, multimethods, normalization, or orchestration split.

---

## Severity 4 — Systemic Complecting

The design forces unrelated changes to happen together across modules.

Examples:

- Adding a new event requires edits in many central files.
- Tests require extensive mocking because time, I/O, and state are hidden.
- Domain rules are inseparable from database, HTTP, or framework code.

Recommendation: propose staged redesign. Do not attempt a massive rewrite in one step.

---

## Severity 5 — Architectural Entanglement

The system’s core model is braided together incorrectly.

Examples:

- Identity and value are confused throughout the domain.
- State transitions are implicit and scattered.
- External representations are treated as the domain model everywhere.
- Core business rules cannot be tested without the full runtime environment.

Recommendation: propose an incremental migration path with explicit boundaries.

---

# Finding Output Format

Every finding should use this exact shape.

```markdown
### Finding: <short name>

**Severity**
<1-5>

**Complected concerns**
- <concern A>
- <concern B>

**Question**
Can we <decomplect via technique>?

**Evidence**
```clojure
<problem snippet>
```

**Suggested refactor**
```clojure
<improved snippet>
```

**Why this is simpler**
<explain reduced entanglement>

**Tradeoffs**
- <cost or risk>
- <when not to apply>
```

---

# Worked Example 1: Pricing with Protocols

## Input Code

```clojure
(defrecord Product [name unit-price qty])
(defrecord Subscription [name monthly-price months])
(defrecord Bundle [name items discount])

(defn subtotal [item]
  (cond
    (instance? Product item)
    (* (:unit-price item) (:qty item))

    (instance? Subscription item)
    (* (:monthly-price item) (:months item))

    (instance? Bundle item)
    (let [raw (reduce + (map subtotal (:items item)))]
      (* raw (- 1 (:discount item))))

    :else
    (throw (ex-info "Unknown item type" {:item item}))))

(defn total [items]
  (reduce + (map subtotal items)))
```

## Finding

### Finding: Type dispatch centralized in `subtotal`

**Severity**
3

**Complected concerns**

- Type identification
- Pricing behavior
- Extensibility of new item kinds

**Question**

Can we decomplect type dispatch via protocols?

**Evidence**

```clojure
(cond
  (instance? Product item) ...
  (instance? Subscription item) ...
  (instance? Bundle item) ...)
```

**Suggested refactor**

```clojure
(defprotocol Priceable
  (subtotal [item]))

(extend-type Product
  Priceable
  (subtotal [item]
    (* (:unit-price item) (:qty item))))

(extend-type Subscription
  Priceable
  (subtotal [item]
    (* (:monthly-price item) (:months item))))

(extend-type Bundle
  Priceable
  (subtotal [item]
    (let [raw (reduce + (map subtotal (:items item)))]
      (* raw (- 1 (:discount item))))))
```

**Why this is simpler**

The `total` function depends only on the operation `subtotal`, not on the full set of possible item types. Adding a new item type does not require editing the central dispatch function.

**Tradeoffs**

- Protocols are appropriate because dispatch is type-based.
- If these were plain maps with `:kind`, a multimethod would likely fit better.

---

# Worked Example 2: Event Handling with Multimethods

## Input Code

```clojure
(defn handle-event! [db event]
  (case (:type event)
    :user-created
    (create-user! db (:payload event))

    :user-deleted
    (delete-user! db (:payload event))

    :invoice-paid
    (mark-paid! db (:payload event))

    (throw (ex-info "Unknown event type" {:event event}))))
```

## Finding

### Finding: Event type dispatch centralized in handler

**Severity**
3

**Complected concerns**

- Event dispatch
- Event-specific behavior
- Central modification point

**Question**

Can we decomplect event dispatch with a multimethod?

**Evidence**

```clojure
(case (:type event)
  :user-created ...
  :user-deleted ...
  :invoice-paid ...)
```

**Suggested refactor**

```clojure
(defmulti handle-event!
  (fn [_db event] (:type event)))

(defmethod handle-event! :user-created [db event]
  (create-user! db (:payload event)))

(defmethod handle-event! :user-deleted [db event]
  (delete-user! db (:payload event)))

(defmethod handle-event! :invoice-paid [db event]
  (mark-paid! db (:payload event)))

(defmethod handle-event! :default [_db event]
  (throw (ex-info "Unknown event type" {:event event})))
```

**Why this is simpler**

The event dispatch rule is explicit and each event handler can vary independently. New event types can be added without editing a central `case`.

**Tradeoffs**

- Multimethods are more dynamic than protocols.
- For a small closed set of event types, `case` may be acceptable.

---

# Worked Example 3: Pure Logic Separated from Effects

## Input Code

```clojure
(defn renew-subscription! [db email-client account]
  (let [new-expiry (plus-months (:expires-at account) 1)
        updated (assoc account :expires-at new-expiry)]
    (save-account! db updated)
    (send-renewal-email! email-client updated)
    updated))
```

## Finding

### Finding: Renewal logic mixed with persistence and email

**Severity**
3

**Complected concerns**

- Subscription renewal calculation
- Database persistence
- Email notification

**Question**

Can pure renewal logic be separated from side effects?

**Evidence**

```clojure
(let [new-expiry ...
      updated ...]
  (save-account! db updated)
  (send-renewal-email! email-client updated)
  updated)
```

**Suggested refactor**

```clojure
(defn renew-subscription [account]
  (let [new-expiry (plus-months (:expires-at account) 1)]
    (assoc account :expires-at new-expiry)))

(defn persist-renewal! [db account]
  (save-account! db account))

(defn notify-renewal! [email-client account]
  (send-renewal-email! email-client account))

(defn renew-subscription! [db email-client account]
  (let [updated (renew-subscription account)]
    (persist-renewal! db updated)
    (notify-renewal! email-client updated)
    updated))
```

**Why this is simpler**

The renewal rule is now a pure value transformation. Persistence and notification are explicit effects at the boundary.

**Tradeoffs**

- More functions are introduced.
- The orchestration function still exists, but it is now thin and obvious.

---

# Worked Example 4: Time Made Explicit

## Input Code

```clojure
(defn should-remind? [task]
  (and (not (:done? task))
       (< (:due-at task) (System/currentTimeMillis))))
```

## Finding

### Finding: Hidden clock dependency

**Severity**
2

**Complected concerns**

- Reminder rule
- Current system time

**Question**

Can time be passed explicitly?

**Evidence**

```clojure
(System/currentTimeMillis)
```

**Suggested refactor**

```clojure
(defn should-remind? [now task]
  (and (not (:done? task))
       (< (:due-at task) now)))
```

**Why this is simpler**

The function is deterministic and easy to test. The system clock is used only at the boundary.

**Tradeoffs**

- Callers must provide `now`.
- This may feel slightly less convenient but reduces hidden dependency.

---

# Worked Example 5: Raw Data Shape Normalization

## Input Code

```clojure
(defn eligible-for-discount? [customer-row]
  (and (= "active" (get-in customer-row [:account :status]))
       (= "pro" (get-in customer-row [:plan :code]))
       (> (get-in customer-row [:billing :last_12_months_cents]) 500000)))
```

## Finding

### Finding: Business rule coupled to storage shape

**Severity**
3

**Complected concerns**

- Raw database/API shape
- Domain business rule
- External naming conventions

**Question**

Can data shape be normalized before applying business rules?

**Evidence**

```clojure
(get-in customer-row [:billing :last_12_months_cents])
```

**Suggested refactor**

```clojure
(defn customer-discount-facts [customer-row]
  {:active? (= "active" (get-in customer-row [:account :status]))
   :plan-code (keyword (get-in customer-row [:plan :code]))
   :annual-spend-cents (get-in customer-row [:billing :last_12_months_cents])})

(defn eligible-for-discount? [{:keys [active? plan-code annual-spend-cents]}]
  (and active?
       (= :pro plan-code)
       (> annual-spend-cents 500000)))
```

**Why this is simpler**

The business rule now speaks in domain terms instead of raw nested storage paths.

**Tradeoffs**

- Requires a normalization layer.
- Worth it when external shapes are unstable or reused across many rules.

---

# Anti-Goals

The hickey skill should not optimize for:

- fewer lines
- cleverness
- novelty
- abstraction for its own sake
- object-oriented patterns copied into Clojure
- framework-shaped code
- premature extensibility

The skill should reject refactors that merely hide complexity behind names.

---

# Refactoring Principles

## Prefer data over code when the branch is static

If a conditional is just mapping values, use a map.

## Prefer protocols when behavior varies by type

If the operation is stable but implementations vary by concrete type, use a protocol.

## Prefer multimethods when behavior varies by data

If dispatch depends on `:type`, `:kind`, multiple arguments, or arbitrary rules, use a multimethod.

## Prefer pure functions in the core

Keep mutation, I/O, time, randomness, and external systems at the boundary.

## Prefer explicit dependencies

Pass `db`, `clock`, `config`, `http-client`, and similar dependencies as arguments.

## Prefer values over identities

Do not introduce mutable identity unless the domain actually requires identity over time.

---

# Example Agent Prompt

```text
You are the "hickey" Clojure refactoring agent.

Analyze the provided Clojure code using Rich Hickey’s idea of decomplecting.

Do not optimize for ease, familiarity, fewer lines, or cleverness.
Optimize for simplicity: fewer intertwined concerns.

Systematically evaluate:

1. Behavior vs representation
2. Type branching vs polymorphism
3. Pure logic vs effects
4. Values vs identity
5. Time vs logic
6. Conditionals vs data
7. Policy vs mechanism
8. Data shape vs business rules
9. Control flow vs data flow
10. Hidden state

For each finding, return:

- finding name
- severity from 1 to 5
- complected concerns
- question
- evidence
- suggested refactor
- why this is simpler
- tradeoffs

Prefer minimal refactors.
Do not invent abstractions unless they remove real entanglement.
```

---

# Summary

The **hickey** skill operationalizes one question:

> What things are braided together here that could vary independently?

The skill is successful when the resulting code has:

- fewer hidden dependencies
- clearer data flow
- more pure transformations
- fewer central coordination points
- explicit boundaries around effects
- abstractions that reduce entanglement rather than conceal it
