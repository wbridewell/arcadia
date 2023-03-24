(ns arcadia.component.minigrid.enter-room-detector
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]))

(defn entry-event []
  {:name "event"
   :arguments {:event-name "enter-room"
               ;:event-lifespan nil
               }
   :type "instance"
   :world nil})

(defrecord MinigridEnterRoomDetector [buffer hallway?]
  Component
  (receive-focus
    [component focus content]
    (when-let [mg-perception (d/first-element content :name "minigrid-perception")]
      ;; only output the event once, when the agent first stands in the doorway
      ;; when the agent is in the doorway, the cell it is on is empty, so we cannot check for door
      ;; we can check for whether the left and right cells are walls
      (cond (and (= "wall" (-> mg-perception :arguments :adjacency-info :left :category))
                 (= "wall" (-> mg-perception :arguments :adjacency-info :right :category))
                 (not @(:hallway? component)))
            (do (reset! (:buffer component) nil)
                (reset! (:hallway? component) true))

            (and (or (not= "wall" (-> mg-perception :arguments :adjacency-info :left :category))
                     (not= "wall" (-> mg-perception :arguments :adjacency-info :right :category)))
                 @(:hallway? component))
            (do (reset! (:buffer component) (entry-event))
                (reset! (:hallway? component) false))

            :else
            (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method MinigridEnterRoomDetector [comp ^java.io.Writer w]
  (.write w (format "MinigridEnterRoomDetector{}")))

(defn start []
  (->MinigridEnterRoomDetector (atom nil) (atom false)))
