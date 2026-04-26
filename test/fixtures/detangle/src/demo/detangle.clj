(ns demo.detangle)

(defrecord Product [])
(defrecord Subscription [])
(defrecord Bundle [])

(defn expired? [token]
  (< (:expires-at token)
     (System/currentTimeMillis)))

(defn handle-event! [event]
  (case (:type event)
    :created :create
    :deleted :delete
    :updated :update))

(defn route-event! [event]
  (cond
    (= (:kind event) :created) :create
    (= (:kind event) :deleted) :delete
    :else :ignore))

(defn subtotal [item]
  (cond
    (instance? Product item) 1
    (instance? Subscription item) 2
    (instance? Bundle item) 3
    :else 0))

(defn eligible? [row]
  (and (= "active" (get-in row [:account :status]))
       (> (get-in row [:billing :last_12_months_cents]) 500000)))

(defn collect-active [users]
  (let [result (atom [])]
    (doseq [u users]
      (when (:active? u)
        (swap! result conj u)))
    @result))

(defn collect-active-explicit-deref [users]
  (let [result (atom [])]
    (doseq [u users]
      (when (:active? u)
        (swap! result conj u)))
    (deref result)))

(defn literal-case [currency]
  (case currency
    :usd "$"
    :eur "€"))

(defn quoted-example []
  '(System/currentTimeMillis))
