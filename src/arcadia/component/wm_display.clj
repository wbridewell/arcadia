(ns arcadia.component.wm-display
  "TODO: add documentation"
  (:import [javax.swing JFrame JPanel JScrollPane JLabel BoxLayout])
  (:require [arcadia.component.core :refer [Component Display merge-parameters]]
            (arcadia.utility [swing :refer [invoke-now]]
                             [possible-relations :as prel]
                             [relations :as rel])
            [clojure.string :refer [replace-first]]))
;;;;
;; On each cycle the object display shows up to N objects that
;; appear in accessible content.

(def ^:parameter display-name "name of the display window" "Events and Relations in Working Memory")
(def ^:parameter x "x position of display" 200)
(def ^:parameter y "y position of display" 200)

;; create a frame large enough to support 2 100x100px images in each cell.
(defn prepare-frame [f xloc yloc]
  (invoke-now
   (doto f
     (.setSize 400 300)
     (.setLocation xloc yloc) ;; some place out of the way.
     (.setVisible true)))
  f)


(defn make-text [elmnt]
  (cond  (and (= (:name elmnt) "change") (contains? (:arguments elmnt) :change-type) (= (:change-type (:arguments elmnt)) "offset"))
         (str "  " (:index (:arguments elmnt)) ": "
              (:property (:arguments elmnt)) " " (:change-type (:arguments elmnt)) " of "
              (get-in elmnt [:arguments :old :arguments :color] "undefined")
              " "
              (get-in elmnt [:arguments :old :arguments :shape] "undefined"))

         (and (= (:name elmnt) "change") (contains? (:arguments elmnt) :change-type))
         (str "  " (:index (:arguments elmnt)) ": "
              (:property (:arguments elmnt)) " " (:change-type (:arguments elmnt)) " of "
              (get-in elmnt [:arguments :new :arguments :color] "undefined")
              " "
              (get-in elmnt [:arguments :new :arguments :shape] "undefined"))

         (= (:name elmnt) "possible-relation")
         (str "  " (prel/possible-relation->text elmnt))

         (= (:name elmnt) "critical-event")
         (str "  Critical Event: " (get-in elmnt [:arguments :text] ""))

         (= (:name elmnt) "collision")
         (str "  " (:index (:arguments elmnt)) ": "
              "Collision between "
              (get-in elmnt [:arguments :agent :arguments :color] "undefined")
              " "
              (get-in elmnt [:arguments :agent :arguments :shape] "undefined")
              " and "
              (get-in elmnt [:arguments :patient :arguments :color] "undefined")
              " "
              (get-in elmnt [:arguments :patient :arguments :shape] "undefined"))

         (= (:name elmnt) "relation")
         (rel/to-string elmnt true true)))

(defn make-label [elmnt]
  (JLabel. (make-text elmnt)))

(defn update-frame [f objs]
  (invoke-now
   (let [p (JPanel.)
         sorted-labels (sort-by #(some-> % .getText (replace-first #"\W" ""))
                                (map make-label objs))]
     (.setLayout p (BoxLayout. p BoxLayout/Y_AXIS))
     (doseq [x sorted-labels] (.add p x))
     (.removeAll (.getContentPane f))
     (.add (.getContentPane f) (JScrollPane. p))
     (.revalidate (.getContentPane f))
     (.repaint (.getContentPane f)))))

(defrecord WMDisplay [frame]
  Display
  (frame [component] (:frame component))

  Component
  (receive-focus
    [component focus content]
    (update-frame (:frame component)
                  (filter #(= (:world %) "working-memory") content)))

  (deliver-result
    [component]))

(defmethod print-method WMDisplay [comp ^java.io.Writer w]
  (.write w (format "WMDisplay{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->WMDisplay
     (prepare-frame
      (invoke-now (JFrame. (:display-name p))) (:x p) (:y p)))))
