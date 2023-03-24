(ns arcadia.component.minigrid.wallfollower
  "TODO"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]
            [clojure.data.generators :as dgen]))

(defn- make-action [id]
  (when id
    {:name "minigrid-action"
     :arguments {:action-command id}
     :world nil
     :type "action"}))


(defn- generate-behavior [{:keys [front left right]} previous-info]
  (cond
    (and (= (:state left) "closed") (= (:category left) "door"))
    (mg/action-value "left")

    (and (= (:state right) "closed") (= (:category right) "door"))
    (mg/action-value "right")

    (and (= (:state front) "open") (= (:category front) "door"))
    (mg/action-value "forward")

    (and (= (:state front) "closed") (= (:category front) "door"))
    (mg/action-value "toggle")

    (and (= (:state left) "open") (= (:category left) "door"))
    (mg/action-value "left")

    (and (= (:state right) "open") (= (:category right) "door"))
    (mg/action-value "right")

    ;; if you get to the end of an impassable space, then
    ;; turn around and go back.
    (every? #{"wall" "door" "lava" "key"}
            [(:category (:left previous-info)) (:category left) (:category front)])
    (mg/action-value "right")

    (every? #{"wall" "door" "lava" "key"}
            [(:category (:right previous-info)) (:category right) (:category front)])
    (mg/action-value "left")

    ;; sometimes it's fine to be random
    (or (#{"wall" "lava" "key"} (:category front))
        (and (= (:category front) "door") (= (:state front) "locked")))
    (dgen/rand-nth [(mg/action-value "right") (mg/action-value "left")])

      ;; the next two conditions will force exploration of hallways or newly open spaces.
    ;; (and (= (:category (:left previous-info)) "wall")
    ;;      (= (:category left) "empty"))
    ;; (mg/action-value "left")

    ;; (and (= (:category (:right previous-info)) "wall")
    ;;      (= (:category right) "empty"))
    ;; (mg/action-value "right")

    (#{"empty"} (:category front))
    (mg/action-value "forward")

    :else
    nil))

(defrecord MinigridWallfollower [buffer memory]
  Component
  (receive-focus
    [component focus content]
  ;(when (d/element-matches? focus :name "object")
  ;    (println "object ##### " focus))
   ;; action in process, so wait for it to complete before requesting a new action
    (if (or (d/element-matches? focus :type "action" :name "minigrid-action" :world nil)
            (d/first-element content :type "environment-action" :name "minigrid-env-action"))
      (reset! buffer nil)
      (when-let [mg-perception (d/first-element content :name "minigrid-perception")]
        (reset! buffer
                (make-action (generate-behavior (-> mg-perception :arguments :adjacency-info) @memory)))
        (reset! memory (-> mg-perception :arguments :adjacency-info)))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method MinigridWallfollower [comp ^java.io.Writer w]
  (.write w (format "MinigridWallfollower{}")))

(defn start []
  (->MinigridWallfollower (atom nil) (atom nil)))
