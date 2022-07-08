(ns arcadia.simulator.environment.jbox2d
  (:require [clojure.math.numeric-tower :as math]
            [arcadia.simulator.environment.core :refer [info step render]]
            [arcadia.utility [image :as img] [general :as g]
                             [image-array :as imgarr] [opencv :as cv]])
  (:import [javax.swing JTextArea]
           [javax.swing.border LineBorder]
           [java.awt Color Polygon Font BasicStroke RenderingHints Rectangle RadialGradientPaint]
           [java.awt.geom Ellipse2D$Double Rectangle2D$Double AffineTransform]
           [java.awt.event KeyListener KeyEvent]
           [org.jbox2d.common Vec2]
           [org.jbox2d.dynamics World BodyDef BodyType FixtureDef]
           [org.jbox2d.collision.shapes PolygonShape CircleShape]
           [org.jbox2d.callbacks ContactListener ContactFilter]
           [java.awt.image BufferedImage]))

(defn state-value
  "Retrieve the value of the key state-key from the environment's state"
  [env state-key]
  (-> env :state deref state-key))

;;-------------------JBox2D parameters-----------------------
(def ^:private velocityIterations 10)
(def ^:private positionIterations 10)

;; ensure all collisions are totally elastic
(set! org.jbox2d.common.Settings/velocityThreshold 0.0)


;;---------------------Rendering Parameters--------------------
(def ^:private pixels-per-meter 60.0)
(defn to-pixels
  "Scale a number in m to a pixel quantity"
  [x]
  (* x pixels-per-meter))
(defn to-meters
  "Scale a pixel quantity to its distance in m"
  [x]
  (/ x pixels-per-meter))


(def default-parameters
  {:increment 1/40 ;; ARCADIA's cycle time
   :width 800
   :height 600
   :scale 1.0   ;; for easily increasing/decreasing resolution
   :background-color Color/GRAY;Color/WHITE
   :wall-thickness 20.0
   :keyboard-force 150.0  ;; pixels/s
   :contact-listeners nil
   :contact-filter
   (proxy [ContactFilter] []
     (shouldCollide [fixtureA fixtureB]
       (and (-> fixtureA .getBody .getUserData :collides?)
            (-> fixtureB .getBody .getUserData :collides?)
            (proxy-super shouldCollide fixtureA fixtureB))))})

(def default-spec
  "A default specification for JBox2D objects, including parameters for
  rendering, shape, physics, programmatic movement, and user-controlled movement."
  {:name nil
   :type nil
   :x 0.0
   :y 0.0
   :x-vel 0.0
   :y-vel 0.0

   :color Color/BLACK
   :outline Color/BLACK
   :outline-weight 2
   :font "Arial"
   :font-style Font/BOLD
   :font-color Color/BLACK
   :font-size 0.6

   ;; Shape parameters- to be complete, a spec must either have
   ;;    a width and height (for a wall/rectangle)
   ;;    a radius (for a circle/regular polygon)
   ;;    an array of [0,0]-centered [x,y] points (for an irregular polygon)
   :width nil
   :height nil
   :radius nil
   :points nil
   :rotation 0.0

   ;; Physics parameters
   :body-type :dynamic ;; (can also be :kinematic or :static)
   :friction 0.0
   :restitution 1.0
   :a-vel 0.0
   :linear-damping 0.0
   :angular-damping 0.0
   :density 0.0
   :collides? true   ;; should this object collide with others?

   ;; Programmatic movement/behavior
   :actions []
   :x-jitter 0.0
   :y-jitter 0.0
   :blink nil
   :blink-rate 5.0

   ;; User-controlled movement
   :user-controlled? false
   :up-ctrl \↑
   :down-ctrl \↓
   :left-ctrl \←
   :right-ctrl \→
   :brake-ctrl \space})

(defn scale-parameters
  "Scales environment parameters according to the :scale value,
  and associates default values for un-mapped parameters."
  [params]
  (g/update-keys params
                 [:width :height :outline-weight :wall-outline-weight
                  :wall-thickness :keyboard-force]
                 #(when % (* % (:scale params)))))

;;--------------------------Wall/Ball Specifications---------------------
;; these are used by the model to specify initial properties of objects
;; to be represented in the billiards environment (positions, velocities, etc)
(defn spec
  "Create a specification"
  [& {:as spec}]
  (g/update-keys (update (merge default-spec spec)
                         :points (partial map (partial mapv to-meters)))
               [:x :y :x-vel :y-vel :a-vel :width :height :radius]
               #(when % (to-meters %))))

(defn wall-spec
  "Create a hashmap specifying the properties of a wall to be created.
   x and y are the center of the wall, and
   w and h are the width and height of the wall.
   For use by the model running this environment.
  NOTE: since walls are implemented as Static/Kinematic bodies in JBox2D, they do not
        respond to forces."
  [name x y w h & {:as args}]
  (g/apply-map spec
               (merge {:name name :type "wall" :body-type :kinematic
                       :friction 0.0 :restitution 0.0 :density 1.0
                       :color Color/BLACK :outline nil :outline-weight 1.5
                       :x x :y y :width (/ w 2) :height (/ h 2)}
                      args)))

(defn ball-spec
  "Create a hashmap specifying the initial properties of a billiard
  ball yet to be created. By default, balls have no friction and
  completely elastic collisions.

  If the key :user-controlled? is true, then the ball can be moved using
  the keys defined by :up-ctrl, :down-ctrl, :left-ctrl, and :right-ctrl."
  ([name color x y] (ball-spec name color x y 0.0 0.0))
  ([name color x y x-vel y-vel & {:as args}]
   (g/apply-map spec
                (merge {:name name :type "ball"
                        :color color :outline Color/BLACK
                        :radius 30 :x x :y y :x-vel x-vel :y-vel y-vel}
                       args))))

(defn scale-spec
  "Resize the necessary parameters for an object's
  specification according to the given scale."
  [spec scale]
  (g/update-keys (update spec :points (partial map (partial mapv (partial * scale))))
                 [:x :y :x-vel :y-vel :width :height :radius]
                 #(some-> % (* scale))))

;;---------------------------JBox2D Wrapper/Factory Functions-------------------
(defn make-world
  "Make a world for the jbox2d objects to reside in.
   Default gravity is none (there is no z axis)"
  ([] (make-world 0.0 0.0))
  ([world] (make-world (-> world .getGravity .-x) (-> world .getGravity .-y)))
  ([x-gravity y-gravity]
   (doto (World. (Vec2. x-gravity y-gravity))
     (.setAllowSleep false))))

(defn get-shape [{:as spec}]
  (cond
    ;; rectangle
    (or (= (:type spec) "wall") (and (:width spec) (:height spec)))
    (doto (PolygonShape.) (.setAsBox (:width spec) (:height spec)))

    ;; circle
    (or (= (:type spec) "ball") (:radius spec))
    (doto (CircleShape.) (.setRadius (:radius spec)))

    ;; polygon
    (seq (:points spec))
    (doto (PolygonShape.) (.set (into-array Vec2 (map #(Vec2. (first %) (second %)) (:points spec)))
                                (count (:points spec))))))

(defn make-obj
  "Create a jbox2d object within the given world from its specification."
  [world {:keys [name x y x-vel y-vel a-vel body-type
                 linear-damping angular-damping rotation
                 friction density restitution]
          :as spec}]
  (let [bodyDef (doto (BodyDef.)
                      (.setType (case body-type
                                  :static BodyType/STATIC
                                  :kinematic BodyType/KINEMATIC
                                  BodyType/DYNAMIC))
                      (.setLinearDamping linear-damping)
                      (.setAngularDamping angular-damping)
                      (.setPosition (Vec2. x y))
                      (.setAngle rotation)
                      (.setLinearVelocity (Vec2. x-vel y-vel))
                      (.setAngularVelocity a-vel))
        fixtureDef (doto (FixtureDef.)
                     (.setShape (get-shape spec))
                     (.setFriction friction)
                     (.setDensity density)
                     (.setRestitution restitution))
        obj (doto (.createBody world bodyDef)
                  (.createFixture fixtureDef)
                  (.setFixedRotation true)
                  (.setBullet true))]
    (doto obj (.setUserData (assoc spec :body obj)))))

(defn- get-color [ball time]
  (if (or (nil? (:blink ball))
          (pos? (Math/sin (* time (:blink-rate ball)))))
    (:color ball)
    (:blink ball)))

(defn- draw-wall
  "Draw a Wall onto a Graphics2D g at the position
  specified by its body in the JBox2D environment"
  [wall g env]
  (let [body (:body wall)
        x (to-pixels (- (.x (.getPosition body)) (:width wall)))
        y (to-pixels (- (.y (.getPosition body)) (:height wall)))
        h (* 2.0 (to-pixels (:height wall)))
        w (* 2.0 (to-pixels (:width wall)))]
    (.setPaint g (get-color wall (state-value env :time)))
    (.fill g (Rectangle2D$Double. x y w h))
    (when (and (:outline wall) (pos? (:outline-weight wall)))
      (.setPaint g (:outline wall))
      (.setStroke g (-> wall :outline-weight BasicStroke.))
      (.draw g (Rectangle2D$Double. x y w h)))))

(defn- draw-ball
  "Draw a BilliardBall onto a Graphics2D g at the position
  specified by its body in the JBox2D environment"
  [ball g env]
  (let [body (:body ball)
        shape (.getShape (.getFixtureList body))
        r (to-pixels (.getRadius shape))
        d (* 2.0 r)
        cx (to-pixels (.x (.getPosition body)))
        cy (to-pixels (.y (.getPosition body)))
        x (- cx r)
        y (- cy r)
        font (Font. (:font ball) (:font-style ball) 30)
        old-transform (.getTransform g)]
    (doto g
     (.setPaint (RadialGradientPaint.
                 (float cx) (float cy) (float r)
                 (float-array [0.0 1.0])
                 (into-array Color [Color/WHITE (get-color ball (state-value env :time))])))
     (.fill (Ellipse2D$Double. x y d d))
     (.setPaint (:outline ball))
     (.setStroke (-> ball :outline-weight BasicStroke.))
     (.draw (Ellipse2D$Double. x y d d))
     (.setPaint (:font-color ball))
     (.setFont (.deriveFont font
                            (float (* (/ (* d (:font-size ball))
                                         (max (-> g (.getFontMetrics font)
                                                  (.stringWidth (:name ball)))
                                              (-> g (.getFontMetrics font) .getHeight)))
                                      (.getSize font)))))
     (.setTransform (doto (AffineTransform.)
                          (.setToTranslation (float cx) (float cy))
                          (.rotate (.getAngle body))))
     (.drawString (:name ball)
                  (-> g .getFontMetrics (.stringWidth (:name ball)) (* -0.5) float)
                  (float (/ (+ (-> g .getFontMetrics .getDescent)
                               (/ (-> g .getFontMetrics .getAscent) 2.0))
                            2.0)))
     (.setTransform old-transform))))

(defn- rectangle-radius
  "Returns the average of height and width of a java Rectangle."
  [^Rectangle rect]
  (/ (+ (dec (.width rect)) (dec (.height rect))) 4))

(defn- draw-polygon
  [obj g env]
  (let [body (:body obj)
        points (take (.getVertexCount (.getShape (.getFixtureList body)))
                     (.getVertices (.getShape (.getFixtureList body))))
        cx (to-pixels (.x (.getPosition body)))
        cy (to-pixels (.y (.getPosition body)))
        polygon (doto (Polygon. (int-array (map #(to-pixels (.-x %)) points))
                                (int-array (map #(to-pixels (.-y %)) points))
                                (count points))
                      (.translate cx cy))
        d (-> polygon .getBounds rectangle-radius (* 2.0))
        font (Font. (:font obj) (:font-style obj) 30)
        old-transform (.getTransform g)]
    (doto g
     (.setPaint (get-color obj (state-value env :time)))
     (.fill polygon)
     (.setPaint (:outline obj))
     (.setStroke (-> obj :outline-weight BasicStroke.))
     (.draw polygon)
     (.setPaint (:font-color obj))
     (.setFont (.deriveFont font
                            (float (* (/ (* d (:font-size obj))
                                         (max (-> g (.getFontMetrics font)
                                                  (.stringWidth (:name obj)))
                                              (-> g (.getFontMetrics font) .getHeight)))
                                      (.getSize font)))))
     (.setTransform (doto (AffineTransform.)
                          (.setToTranslation (float cx) (float cy))
                          (.rotate (.getAngle body))))
     (.drawString (:name obj)
                  (-> g .getFontMetrics (.stringWidth (:name obj)) (* -0.5) float)
                  (float (/ (+ (-> g .getFontMetrics .getDescent)
                               (/ (-> g .getFontMetrics .getAscent) 2.0))
                            2.0)))
     (.setTransform old-transform))))

(defn get-canvas
  "A default function for getting a blank canvas for rendering."
  [env]
  (let [bi (BufferedImage. (-> env info :width) (-> env info :height) BufferedImage/TYPE_3BYTE_BGR)]
    (doto (.createGraphics bi)
          (.setPaint (-> env info :background-color))
          (.fillRect 0 0 (-> env info :width) (-> env info :height)))
    bi))

(defn draw-screen
  "Returns an image displaying all simulated objects in the environment.

  If the function get-canvas is defined in env-ns, it will be used to get the background image.
  get-canvas should take the env as an argument, and return a BufferedImage
  upon which to render the environment. By default, the background is rendered
  as a blank white screen.

  If the function draw-foreground is defined in env-ns, it will be called
  after all objects are drawn on top of the canvas. draw-foreground is expected
  to accept the environment and a Graphics2D object as arguments."
  [env-ns env]
  (let [canvas ((or (ns-resolve env-ns 'get-canvas) get-canvas) env)
        g (.createGraphics canvas)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    ;; draw all objects, starting with walls
    (doseq [obj (sort-by #(:type (.getUserData %)) (comp - compare)
                         (state-value env :objects))]
      (cond
        (or (= (:type (.getUserData obj)) "wall")
            (and (:width (.getUserData obj)) (:height (.getUserData obj))))
        (draw-wall (.getUserData obj) g env)

        (or (= (:type (.getUserData obj)) "ball")
            (:radius (.getUserData obj)))
        (draw-ball (.getUserData obj) g env)

        (:points (.getUserData obj))
        (draw-polygon (.getUserData obj) g env)))
    ;; draw the foreground (if any)
    (when (ns-resolve env-ns 'draw-foreground)
      ((ns-resolve env-ns 'draw-foreground) env g))
    (when (:text-box env) (.print (:text-box env) g))
    (.dispose g)
    canvas))




;;------------------------------------------------------------------------------
;;--------------------------User-Defined Object Action--------------------------
(defn make-jitter [obj]
  (.setLinearVelocity obj (Vec2. (-> obj .getLinearVelocity .x
                                     (g/normal (-> obj .getUserData :x-jitter)))
                                 (-> obj .getLinearVelocity .y
                                     (g/normal (-> obj .getUserData :y-jitter))))))

;; an Action is an action by an object in the environment.
;; apply-action makes the appropriate change to the object/environment.
(defprotocol Action (apply-action [a obj env]))

;; NOTE: all x and y values are treated as pixels/s, and converted to m/s for JBox2D.

;; Add to/subtract from the object's current velocity at time t.
(defrecord VelocityChange [dx dy t] Action
  (apply-action [a obj env]
                (.setLinearVelocity obj (.add (.getLinearVelocity obj)
                                              (Vec2. (* (to-meters dx) (-> env info :scale))
                                                     (* (to-meters dy) (-> env info :scale)))))))
;;Reset the object's velocity to [x, y] at time t
(defrecord VelocityReset [x y t] Action
  (apply-action [a obj env]
                (.setLinearVelocity obj (Vec2. (* (to-meters x) (-> env info :scale))
                                               (* (to-meters y) (-> env info :scale))))))

;; Apply an impulse [x, y] to the object at time t
(defrecord Impulse [x y t] Action
  (apply-action [a obj env]
                (.applyLinearImpulse obj (Vec2. (* (to-meters x) (-> env info :scale))
                                                (* (to-meters y) (-> env info :scale)))
                                     (.getWorldCenter obj) true)))

;; Apply a force [x, y] to the object from time t to time (+ t duration)
(defrecord Force [x y t duration] Action
  (apply-action [a obj env]
                (.applyForceToCenter obj (Vec2. (* (to-meters x) (-> env info :scale))
                                                (* (to-meters y) (-> env info :scale))))))

;; Reduce the object's speed by damping factor f from time t to time (+ t duration)
(defrecord Brake [f t duration] Action
  (apply-action [a obj env] (.setLinearDamping obj f)))

;; Have the object "say" something at time t
;; EDIT: if env is nil, just return the string
(defrecord Speech [recipient s t] Action
  (apply-action [a obj env]
                (some-> env :text-box
                        (.append (str " " (:name (.getUserData obj)) " to " recipient ": " s "\n")))
                (str (:name (.getUserData obj)) " to " recipient ": " s)))

;; Start/stop blinking at time t
(defrecord Blink [color t] Action
  (apply-action [a ball env]
                (.setUserData ball (if color
                                     (-> ball .getUserData (assoc :blink color))
                                     (-> ball .getUserData (dissoc :blink))))))

(defn- bool->int [b]
  (if b 1 0))

(defn- key-pressed? [dir-keywd obj actions]
  (-> obj .getUserData dir-keywd hash-set (some actions)))

(defn- keypresses->actions
  "Given a set of action characters, produces all actions applicable to the object."
  [obj keys env]
  (when (-> obj .getUserData :user-controlled?)
    ;; convert the action commands into x/y acceleration directions
    (let [x-dir (- (bool->int (key-pressed? :right-ctrl obj keys))
                   (bool->int (key-pressed? :left-ctrl obj keys)))
          y-dir (- (bool->int (key-pressed? :down-ctrl obj keys))
                   (bool->int (key-pressed? :up-ctrl obj keys)))
          brake? (key-pressed? :brake-ctrl obj keys)]
      (remove nil?
              [(let [x0 (-> obj .getFixtureList (.getAABB 0) .-lowerBound .-x to-pixels)
                     y0 (-> obj .getFixtureList (.getAABB 0) .-lowerBound .-y to-pixels)
                     x1 (-> obj .getFixtureList (.getAABB 0) .-upperBound .-x to-pixels)
                     y1 (-> obj .getFixtureList (.getAABB 0) .-upperBound .-y to-pixels)]
                 (->VelocityReset (if (or (zero? x-dir)
                                          (and (neg? x-dir) (<= x0 0))
                                          (and (pos? x-dir) (>= x1 (-> env info :width))))
                                    0 (* x-dir (-> env info :keyboard-force)))
                                  (if (or (zero? y-dir)
                                          (and (neg? y-dir) (<= y0 0))
                                          (and (pos? y-dir) (>= y1 (-> env info :height))))
                                    0 (* y-dir (-> env info :keyboard-force)))
                                  (state-value env :time)))
               (when brake? (->VelocityReset 0 0 (state-value env :time)))]))))


(defn- apply-action?
  "Returns true if the action's time overlaps the current time."
  [action time time-step]
  (or (and (< (- time time-step) (:t action))
           (<= (:t action) time))
      (and (:duration action)
           (< (:t action) (- time time-step))
           (<= (- time time-step)
               (+ (:t action) (:duration action))))))

(defn apply-actions
  [obj env keys]
  (doseq [a (concat (-> obj .getUserData :actions)
                    (keypresses->actions obj keys env))
          :when (apply-action? a (state-value env :time) (-> env info :increment))]
    ;(if (and (< (- time time-step) (:t a))
     ;        (<= (:t a) time)]
      ;(println (format "APPLYING ACTION %s %.2f" (-> obj .getUserData :name) (float time)) a)
    (apply-action a obj env)))




;;------------------------------------------------------------------------------
;;-----------------------------Environment Functions----------------------------
;; this ContactListener stores a list of ContactListeners to call whenever
;; two objects contact in the environment. This is necessary because
;; JBox2D only allows a World to have one ContactListener.
(defrecord ContactListenerDispatcher [listeners] ContactListener
  (beginContact [listener contact]
    (doseq [l listeners] (.beginContact l contact)))
  (endContact [listener contact]
    (doseq [l listeners] (.endContact l contact)))
  (preSolve [listener contact manifold]
    (doseq [l listeners] (.preSolve l contact manifold)))
  (postSolve [listener contact contact-impulse]
    (doseq [l listeners] (.postSolve l contact contact-impulse))))

(defn set-contact-listeners [world & listeners]
  (.setContactListener world (->ContactListenerDispatcher listeners)))

(defn add-contact-listener
  "Add a contact listener to an environment's World.
  If the listener has an :env field, set the field to the current environment.
  This gives the contact listener access to the environment's state at collision."
  [env listener]
  (when (and (:env listener) (instance? clojure.lang.IAtom (:env listener)))
    (reset! (:env listener) env))
  (set-contact-listeners (state-value env :world)
                         (->ContactListenerDispatcher
                          (conj (:listeners (.-m_contactListener (.getContactManager (state-value env :world))))
                                listener))))

(defn new-state
  "Create a map storing the current state of the
  JBox2D World and the Bodies contained in it."
  ([specs] (new-state specs nil nil))
  ([specs contact-listeners] (new-state specs contact-listeners nil))
  ([specs contact-listeners contact-filter]
   (let [world (doto (make-world)
                     (.setContactListener (->ContactListenerDispatcher contact-listeners))
                     (.setContactFilter contact-filter))]
     {:world world
      :objects (doall (map #(make-obj world %) specs))
      :time 0})))

(defn step-default
  "A default function for stepping in a JBox2D environment"
  [env actions]
  (doseq [obj (state-value env :objects)]
    (.setLinearDamping obj (-> obj .getUserData :linear-damping)) ;; reset to default surface friction
    (apply-actions obj env actions)
    (make-jitter obj))

  ;; advance the physics engine and increment time
  (.step (state-value env :world) (-> env info :increment) velocityIterations positionIterations)
  (swap! (:state env) update :time (->> env info :increment (partial +)))
  (swap! (:state env) assoc :actions actions)
  {:observation @(:state env) :reward 0 :done? false})

(defn render-default
  "A default function for rendering a JBox2D environment. Requires that the function
  get-canvas be defined in the environment's namespace env-ns."
  [env-ns env mode close]
  (case mode
    "human" (img/display-image! (:jframe env) (draw-screen env-ns env))

    "buffered-image" {:image (draw-screen env-ns env)
                      :location {:xpos (-> env info :width (/ 2) math/floor)
                                 :ypos (-> env info :width (/ 2) math/floor)
                                 :xcorner 0
                                 :ycorner 0}}

    "opencv-matrix"
    {:image (img/bufferedimage-to-mat (draw-screen env-ns env) cv/CV_8UC3)
     :location {:xpos (-> env info :width (/ 2) math/floor)
                :ypos (-> env info :width (/ 2) math/floor)
                :xcorner 0
                :ycorner 0}}

    "action" (state-value env :actions)
    "state" @(:state env)

    ;; for text rendering, return a seq of all strings
    ;; currently being communicated in the environment
    "text" (some->> (state-value env :objects)
                    (mapcat (fn [obj] (map #(vector % obj) (-> obj .getUserData :actions))))
                    (filter #(and (instance? Speech (first %))
                                  (apply-action? (first %) (state-value env :time)
                                                 (-> env info :increment))))
                    ;; apply the action on a nil environment, so that
                    ;; rendering the action doesn't modify env's state
                    (map #(apply-action (first %) (second %) nil))
                    (filter some?))))

(defn reset-default
  "A default function for resetting a JBox2D environment"
  [env]
  (reset! (:state env) (new-state (:specs (info env)))))

(defn text-box
  "Create a text-box for displaying speech in the environment"
  [{:keys [text-box? scale text-box-x text-box-y text-box-w text-box-h]}]
  ;; text box creation
  (if text-box?
    (doto (JTextArea.)
          (.setBounds (* scale text-box-x) (* scale text-box-y)
                      (* scale text-box-w) (* scale text-box-h))
          (.setBorder (LineBorder/createBlackLineBorder))
          (.setFont (Font. Font/MONOSPACED Font/PLAIN 18))
          (.setVisible true))
    (JTextArea.)))







;----------------------------Keyboard Event Handling----------------------------
(defn- get-key-char
  "Extract the character of the key currently pressed in this KeyEvent."
  [e]
  (let [c (-> e .getKeyCode KeyEvent/getKeyText first)]
    ;; fixes a weird bug with getKeyText
    (if (= c \␣) \space c)))

(defrecord KeyControl [pressed-keys] KeyListener
  (keyPressed [ctrl e]
              (reset! pressed-keys (set (conj @pressed-keys (get-key-char e)))))
  (keyReleased [ctrl e]
               (reset! pressed-keys (set (remove #{(get-key-char e)} @pressed-keys))))
  (keyTyped [ctrl e]))





;;------------------------------------------------------------------------------
(defn run-environment
  "Run a JBox2D environment in a JFrame for a number of cycles."
  [env-ns cycles & {:keys [sleep] :or {sleep 10} :as env-args}]
  (doall (map #(.dispose %) (java.awt.Window/getWindows))) ;; dispose old frames
  ;; set up the environment
  (let [env ((ns-resolve env-ns 'configure)
             (assoc env-args :text-box? (or (:text-box? env-args)
                                            (some #(some (partial instance? Speech) (:actions %))
                                                  (:specs env-args)))))
        ctrl (->KeyControl (atom nil))]
    (-> env :jframe .getContentPane (.addKeyListener ctrl))
    (-> env :jframe .getContentPane .requestFocusInWindow)
    (-> env :jframe (.setVisible true))
    ;; run the simulation
    (dotimes [t cycles]
      (-> env :jframe .getContentPane .requestFocusInWindow)
      (Thread/sleep sleep)
      (render env "human" nil)
      (step env @(:pressed-keys ctrl)))))

(defn save-environment
  "Run a JBox2D environment in a JFrame for a number of cycles, and
  save all of the frames into the \"images\" folder."
  [env-ns cycles & {:keys [sleep directory] :or {sleep 10 directory "images"} :as env-args}]
  (doall (map #(.dispose %) (java.awt.Window/getWindows))) ;; dispose old frames
  (imgarr/delete-images) (imgarr/reset-images)
  (let [env ((ns-resolve env-ns 'configure)
             (assoc env-args :text-box? (or (:text-box? env-args)
                                            (some #(some (partial instance? Speech) (:actions %))
                                                  (:specs env-args)))))
        ctrl (->KeyControl (atom nil))]
    (-> env :jframe .getContentPane (.addKeyListener ctrl))
    (-> env :jframe .getContentPane .requestFocusInWindow)
    (-> env :jframe (.setVisible true))
    ;; run the simulation
    (dotimes [t cycles]
      (-> env :jframe .getContentPane .requestFocusInWindow)
      (Thread/sleep sleep)
      (imgarr/add-image (:image (render env "buffered-image" nil)) "billiards")
      (render env "human" nil)
      (step env @(:pressed-keys ctrl)))
    (imgarr/save-images :directory directory :img-h (-> env info :height) :img-w (-> env info :width))))
