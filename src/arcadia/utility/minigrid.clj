(ns
 ^{:doc "Functions for working with the minigrid environment."}
 arcadia.utility.minigrid
  (:require [arcadia.utility.descriptors :as d]))

(def ^:private actions ["left" "right" "forward" "pickup" "drop" "toggle" "done"])
(def ^:private object-categories ["unseen" "empty" "wall" "floor" "door" "key" "ball"
                                  "box" "goal" "lava" "agent"])
(def ^:private color-names ["red" "green" "blue" "purple" "yellow" "grey"])
(def ^:private door-state ["open" "closed" "locked"])
(def ^:private directions ["right" "down" "left" "up"])

(def ^:private object-channel 0)
(def ^:private color-channel 1)
(def ^:private door-channel 2)

(defn action-name [mga]
  (get actions mga "unknown"))

(defn action-value [name]
  (.indexOf actions name))

(defn category [mgc] 
  (if (sequential? mgc)
    (get object-categories (get mgc object-channel) "unknown")
    (get object-categories mgc "unknown")))

(defn color [mgc]
  (if (sequential? mgc)
    (get color-names (get mgc color-channel) "unknown")
    (get color-names mgc "unknown")))

(defn door [mgs]
  (if (sequential? mgs)
    (get door-state (get mgs door-channel) "unknown")
    (get door-state mgs "unknown")))

(defn direction [mgd]
  (get directions mgd "unknown"))

(defn inventory [content]
  (-> (d/first-element content :name "minigrid-perception")
      :arguments :adjacency-info :on))