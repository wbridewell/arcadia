(ns arcadia.component.minigrid.wallfollower
  "TODO"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]))

(defn- make-action [id source]
  (when id
    {:name "minigrid-action"
     :arguments {:action-command id}
     :world nil
     :source source
     :type "action"}))


(defn- generate-behavior [adjacency-info]
  (let [forward (:front adjacency-info)
        ontop (:on adjacency-info)
        left (:left adjacency-info)
        right (:right adjacency-info)]
    (cond
      (= (:category ontop) "goal")
      (mg/action-value "done")

      (= (:category forward) "goal")
      (mg/action-value "done")

      (= (:category left) "goal")
      (mg/action-value "left")

      (= (:category right) "goal")
      (mg/action-value "right")

      (= (:category forward) "key")
      (mg/action-value "pickup")

      (and (= (:state left) "closed") (= (:category left) "door"))
      (mg/action-value "left")

      (and (= (:state right) "closed") (= (:category right) "door"))
      (mg/action-value "right")

      (and (= (:state forward) "open") (= (:category forward) "door"))
      (mg/action-value "forward")

      (and (= (:state forward) "closed") (= (:category forward) "door"))
      (mg/action-value "toggle")

      (and (= (:state left) "open") (= (:category left) "door"))
      (mg/action-value "left")

      (and (= (:state right) "open") (= (:category right) "door"))
      (mg/action-value "right")

      (and (= (:category forward) "door") (= (:state forward) "locked")
           (= (:category ontop) "key") (= (:color ontop) (:color forward)))
      (mg/action-value "toggle")

      (or (#{"wall" "lava"} (:category forward))
          (and (= (:category forward) "door") (= (:state forward) "locked")))
      (rand-nth [(mg/action-value "right") (mg/action-value "left") (mg/action-value "right")])

      (#{"empty"} (:category forward))
      (mg/action-value "forward")

      :else
      nil)))

(defrecord MinigridWallfollower [buffer forward?]
  Component
  (receive-focus
    [component focus content]
  ;(when (d/element-matches? focus :name "object")
  ;    (println "object ##### " focus))
   ;; action in process, so wait for it to complete before requesting a new action
    (if (or (d/element-matches? focus :type "action" :name "minigrid-action" :world nil)
            (d/first-element content :type "environment-action" :name "minigrid-env-action"))
      (reset! (:buffer component) nil)
      (when-let [mg-perception (d/first-element content :name "minigrid-perception")]
        (reset! (:buffer component)
                (make-action (generate-behavior (-> mg-perception :arguments :adjacency-info)) component)))))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method MinigridWallfollower [comp ^java.io.Writer w]
  (.write w (format "MinigridWallfollower{}")))

(defn start []
  (->MinigridWallfollower (atom nil) (atom false)))
