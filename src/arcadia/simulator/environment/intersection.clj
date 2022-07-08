(ns arcadia.simulator.environment.intersection
  "Defines a simulation environment for a billiard ball table"
  (:require [arcadia.utility.vectors :as vec]
            [arcadia.simulator.environment.jbox2d :as jbox2d]
            [arcadia.simulator.environment.core :refer [Environment info step]]
            [arcadia.utility.general :as g])
  (:import [javax.swing JFrame]
           [java.awt Color Polygon]
           [org.jbox2d.callbacks ContactListener]))

(def collision-points (atom nil))
(defn- add-collision-point [p]
  (->> (swap! collision-points conj p)
       (g/group #(and (g/near-equal? (first %1) (first %2) 100)
                      (g/near-equal? (second %1) (second %2) 100)))
       (map #(vec/scalar-div (map (partial apply +) (g/seq-transpose %)) (count %)))
       (reset! collision-points)))

(defn make-polygon [points]
  (Polygon. (int-array (map first points))
            (int-array (map second points))
            (count points)))

(defrecord ExplosionListener [] ContactListener
           (beginContact [listener contact]
             (when (.isTouching contact)
               (let [c1 (.getWorldCenter (.getBody (.getFixtureA contact)))
                     c2 (.getWorldCenter (.getBody (.getFixtureB contact)))
                     p (.mul (.add c1 c2) 0.5)
                     uncle (cond
                             (-> contact .getFixtureA .getBody .getUserData :name (= "U"))
                             (-> contact .getFixtureA .getBody)

                             (-> contact .getFixtureB .getBody .getUserData :name (= "U"))
                             (-> contact .getFixtureB .getBody))
                     me (cond
                          (-> contact .getFixtureA .getBody .getUserData :name (= "B"))
                          (-> contact .getFixtureA .getBody)

                          (-> contact .getFixtureB .getBody .getUserData :name (= "B"))
                          (-> contact .getFixtureB .getBody))
                     witness (cond
                               (-> contact .getFixtureA .getBody .getUserData :name (= "W"))
                               (-> contact .getFixtureA .getBody)

                               (-> contact .getFixtureB .getBody .getUserData :name (= "W"))
                               (-> contact .getFixtureB .getBody))]
                 (when me
                   (.setUserData me
                                 (-> me .getUserData (assoc :y-jitter 0))))

                 (when witness
                   (.setUserData witness
                                 (-> witness .getUserData (assoc :color Color/PINK :linear-damping -10))))

                 (when uncle
                   (.setUserData uncle
                                 (-> uncle .getUserData (assoc :color Color/PINK :linear-damping -10))))

                 (add-collision-point [(jbox2d/to-pixels (.-x p)) (jbox2d/to-pixels (.-y p))]))))
           (endContact [listener contact])
           (preSolve [listener contact manifold])
           (postSolve [listener contact contact-impulse]))

(def default-parameters
  {:road-color (.darker Color/GRAY)
   :stripe-color (:background-color jbox2d/default-parameters)
   :road-wall-color (:background-color jbox2d/default-parameters)
   :contact-listeners [(->ExplosionListener)]})

(defrecord Intersection [state jframe parameters]
  Environment
  (info [env]
        (assoc parameters
               :dimensions [:height :width]
               :viewing-height 13.5
               :viewing-width 18
               :render-modes ["human" "buffered-image" "opencv-matrix" "text"]))

  ;; returns [observation reward done? diagnostic-info]
  (step [env actions] (jbox2d/step-default env actions))
  (reset [env] (jbox2d/reset-default env))
  (render [env mode close] (jbox2d/render-default 'arcadia.simulator.environment.intersection env mode close))
  (close [env] (reset! state {}))
  ;; no randomness
  (seed [env seed-value] nil))

;;-------------------------------Rendering-----------------------------------

;;------------------------------------------------------------------------------
;; wall-specs is a seq of hashmaps created by the function wall-spec
;; ball-specs is a seq of hashmaps created by the function ball-spec
;; advance is the # of seconds to pre-run the environment
(defn configure
  "Configure the road environment taking wall-specs, ball-specs,
  and advance as keyword arguments. the :advance argument instructs the
  environment to run for :advance seconds before starting the model."
  [{:keys [specs advance x y]
    :or {specs [] advance 0.0 x 0.0 y 0.0}
    :as env-args}]
  (reset! collision-points nil)
  (let [parameters (merge jbox2d/default-parameters default-parameters env-args)
        scaled-parameters (jbox2d/scale-parameters parameters)
        scaled-specs (map #(jbox2d/scale-spec % (:scale parameters)) specs)
        env (->Intersection (atom (jbox2d/new-state scaled-specs
                                                    (into (:contact-listeners default-parameters)
                                                          (:contact-listeners env-args))))
                            (doto (JFrame. "Road Environment")
                              (.setSize (:width scaled-parameters) (:height scaled-parameters))
                              (.setLocation x y))
                            (assoc scaled-parameters :x x :y y
                                   :wall-specs scaled-specs))]
    ;; (-> env :state deref :world (.setContactListener (->ExplosionListener)))
    ;; advance the environment.
    (dotimes [i (/ advance (-> env info :increment))] (step env nil))
    env))
