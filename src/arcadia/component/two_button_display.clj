(ns arcadia.component.two-button-display
  "This component was developed for cued task-switching to show the actions
  that are taking place in the environment.

  Focus Responsive
    * push-button, name & action, type

  Graphically displays the pressing of a button associated with the action.

  Default Behavior
  None.

  Produces
  Nothing

  Displays
  Two buttons, one labeled \"Low/Odd\" and another \"High/Even\".
  When a button is \"pressed,\" it is held down for several milliseconds so that
  the activity registers with the visual display and is obvious to a person
  viewing the system's behavior."
  (:import [javax.swing JFrame JButton BoxLayout BorderFactory JPanel])
  (:require [arcadia.component.core :refer [Component Display merge-parameters]]
            (arcadia.utility [general :as gen]
                             [swing :as swing])))

;; NOTE: Ideally, this would be part of the environment and may be moved there
;; in the near future. When that happens, we will want to add a component that
;; reports an "enacted" element for the executed action.

(def ^:parameter x "x position of button display" 0)
(def ^:parameter y "y position of button display" 0)

(def ^:private b1 (JButton. "Low/Odd"))
(def ^:private b2 (JButton. "High/Even"))

(defn- swing-object-panel
  "Helper function for drawing the button panel."
  []
  ;; Must be called within the Swing EDT
  (let [main-p (JPanel.)]
    (doto main-p
       (.setLayout (BoxLayout. main-p BoxLayout/X_AXIS))
       (.setBorder (BorderFactory/createEtchedBorder))
       (.add b1)
       (.add b2))))

(defn- prepare-frame
  "Initialize a frame for displaying two buttons."
  [f xloc yloc]
  (swing/invoke-now
       (doto f
        (.setSize 215 60)
        (.setLocation xloc yloc)
        (.add (swing-object-panel))
        (.setVisible true)))
  f)

(defn- update-frame
  "Respond to button presses."
  [frame env-action]
  (swing/invoke-now
      ;; either even-high or odd-low is pressed for the target model, so
      ;; if we're here, it's mutually exclusive.
      (if (= (-> env-action :arguments :action-command) :b-even-high)
        (.doClick b2 500)
        (.doClick b1 500))
      (doto (.getContentPane frame)
        (.revalidate)
        (.repaint))))

(defrecord TwoButtonDisplay [frame]
  Display
  (frame [component] (:frame component))

  Component
  (receive-focus
   [component focus content]
    ;; ignore content
   (when-let [ea (gen/find-first #(and (= (:type %) "environment-action")
                                       (= (:name %) "push-button"))
                         content)]
     (update-frame (:frame component) ea)))

  (deliver-result [component]))

(defmethod print-method TwoButtonDisplay [comp ^java.io.Writer w]
  (.write w (format "TwoButtonDisplay{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->TwoButtonDisplay (prepare-frame
                         (swing/invoke-now (JFrame. "Task Switching Buttons"))
                         (:x p) (:y p)))))
