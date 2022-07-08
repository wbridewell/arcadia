(ns arcadia.sensor.touch
  (:require [arcadia.simulator.environment.jbox2d :as jbox2d]
            [arcadia.sensor.core :refer [Sensor]])
  (:import org.jbox2d.callbacks.ContactListener))

(defrecord TouchListener [sensor] ContactListener
  (beginContact [listener contact]
    (let [obj1 (.getUserData (.getBody (.getFixtureA contact)))
          obj2 (.getUserData (.getBody (.getFixtureB contact)))]
      (cond
        (and (.isTouching contact) (= (:name obj1) (:object-label sensor)))
        (swap! (:contact-labels sensor) conj (:name obj2))

        (and (.isTouching contact) (= (:name obj2) (:object-label sensor)))
        (swap! (:contact-labels sensor) conj (:name obj1)))))
  (endContact [listener contact]
    (let [obj1 (.getUserData (.getBody (.getFixtureA contact)))
          obj2 (.getUserData (.getBody (.getFixtureB contact)))]
      (cond
        (= (:name obj1) (:object-label sensor))
        (swap! (:contact-labels sensor) disj (:name obj2))

        (= (:name obj2) (:object-label sensor))
        (swap! (:contact-labels sensor) disj (:name obj1)))))
  (preSolve [listener contact manifold])
  (postSolve [listener contact contact-impulse]))

(defrecord Touch [env object-label contact-labels] Sensor
  (poll [sensor] @(:contact-labels sensor))
  (swap-environment
   [sensor new-env]
   (reset! (:env sensor) new-env)
   (jbox2d/add-contact-listener new-env (->TouchListener sensor))))

(defn start [environment]; object-label]
  (let [sensor (->Touch (atom environment) "touch" (atom #{}))]
    (jbox2d/add-contact-listener environment (->TouchListener sensor))
    sensor))
