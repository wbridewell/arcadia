globals [
  tick-delta        ;; how much simulation time will pass in this step
  box-edge          ;; distance of box edge from origin
  collisions        ;; list used to keep track of future collisions
  particle1         ;; first particle currently colliding
  particle2         ;; second particle currently colliding
]

breed [particles particle]

particles-own [
  speed
  target
  padded-size
]



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;; setup procedures ;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

to setup
  clear-all
  set-default-shape particles "circle"
  set box-edge max-pxcor - 1
  ask patches with [(abs pxcor = box-edge or abs pycor = box-edge) and
                    abs pxcor <= box-edge and abs pycor <= box-edge]
    [ set pcolor black ]
  reset-ticks
  make-particles
  position-particles
  set particle1 nobody
  set particle2 nobody
  set collisions []
  ask particles [ check-for-wall-collision ]
  ask particles [ check-for-particle-collision ]
end

to make-particles
  create-particles number-targets [
    set target true
  ]
  create-particles (initial-number-particles - number-targets) [
    set target false
  ]
  ask particles [
    set speed particle-speed
    set size particle-size
    set padded-size size + padding-size
    recolor
  ]

end

to position-particles
  ask particles [
    position-randomly
    while [overlapping?] [ position-randomly ]
  ]
end

to-report overlapping?  ;; particle procedure
  ;; here, we use IN-RADIUS just for improved speed; the real testing
  ;; is done by DISTANCE
  report any? other particles in-radius padded-size
                              with [distance myself < padded-size]
end

to position-randomly  ;; particle procedure
  ;; place particle at random location inside the box
  setxy one-of [1 -1] * random-float (box-edge - 0.5 - padded-size / 2)
        one-of [1 -1] * random-float (box-edge - 0.5 - padded-size / 2)
end


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;; go procedures  ;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

to go
  loop [
    ifelse ticks < (iterations - 50)  [
      choose-next-collision
      ask particles [
        jump speed * tick-delta
        recolor
      ]
      perform-next-collision
      tick-advance tick-delta
      recalculate-particles-that-just-collided
    ]
    [
      tick
      ask particles [ recolor ]
    ]
    if ticks > iterations [
      stop
    ]
  ]
end

to recalculate-particles-that-just-collided
  ;; Since only collisions involving the particles that collided most recently could be affected,
  ;; we filter those out of collisions.  Then we recalculate all possible collisions for
  ;; the particles that collided last.  The ifelse statement is necessary because
  ;; particle2 can be either a particle or a string representing a wall.  If it is a
  ;; wall, we don't want to invalidate all collisions involving that wall (because the wall's
  ;; position wasn't affected, those collisions are still valid.
  ifelse is-turtle? particle2
    [
      set collisions filter [item 1 ? != particle1 and
                             item 2 ? != particle1 and
                             item 1 ? != particle2 and
                             item 2 ? != particle2]
                            collisions
      ask particle2 [ check-for-wall-collision ]
      ask particle2 [ check-for-particle-collision ]
    ]
    [
      set collisions filter [item 1 ? != particle1 and
                             item 2 ? != particle1]
                            collisions
    ]
  if particle1 != nobody [ ask particle1 [ check-for-wall-collision ] ]
  if particle1 != nobody [ ask particle1 [ check-for-particle-collision ] ]
  ;; Slight errors in floating point math can cause a collision that just
  ;; happened to be calculated as happening again a very tiny amount of
  ;; time into the future, so we remove any collisions that involves
  ;; the same two particles (or particle and wall) as last time.
  set collisions filter [item 1 ? != particle1 or
                         item 2 ? != particle2]
                        collisions
  ;; All done.
  set particle1 nobody
  set particle2 nobody
end

;; check-for-particle-collision is a particle procedure that determines the time it takes
;; to the collision between two particles (if one exists).  It solves for the time by representing
;; the equations of motion for distance, velocity, and time in a quadratic equation of the vector
;; components of the relative velocities and changes in position between the two particles and
;; solves for the time until the next collision
to check-for-particle-collision
  let my-x xcor
  let my-y ycor
  let my-particle-size padded-size
  let my-x-speed speed * dx
  let my-y-speed speed * dy
  ask other particles
  [
    let dpx (xcor - my-x)   ;; relative distance between particles in the x direction
    let dpy (ycor - my-y)    ;; relative distance between particles in the y direction
    let x-speed (speed * dx) ;; speed of other particle in the x direction
    let y-speed (speed * dy) ;; speed of other particle in the x direction
    let dvx (x-speed - my-x-speed) ;; relative speed difference between particles in x direction
    let dvy (y-speed - my-y-speed) ;; relative speed difference between particles in y direction
    let sum-r (((my-particle-size) / 2 ) + (([padded-size] of self) / 2 )) ;; sum of both particle radii


    let p-squared   ((dpx * dpx) + (dpy * dpy)) - (sum-r ^ 2)   ;; p-squared represents difference
    ;; of the square of the radii and the square of the initial positions

    let pv  (2 * ((dpx * dvx) + (dpy * dvy)))  ;; vector product of the position times the velocity
    let v-squared  ((dvx * dvx) + (dvy * dvy)) ;; the square of the difference in speeds

    let d1 pv ^ 2 -  (4 * v-squared * p-squared)

    let time-to-collision  -1

    if d1 > 0
      [ set time-to-collision (- pv - sqrt d1) / (2 * v-squared) ]        ;; solution for time step

    if time-to-collision > 0
    [
      set collisions fput (list (time-to-collision + ticks) self myself)
                          collisions
    ]
  ]
end


;; determines when a particle will hit any of the four walls
to check-for-wall-collision  ;; particle procedure
  ;; right & left walls
  let x-speed (speed * dx)
  if x-speed != 0
    [ ;; solve for how long it will take particle to reach right wall
      let right-interval (box-edge - 0.5 - xcor - padded-size / 2) / x-speed
      if right-interval > 0
        [ assign-colliding-wall right-interval "right wall" ]
      ;; solve for time it will take particle to reach left wall
      let left-interval ((- box-edge) + 0.5 - xcor + padded-size / 2) / x-speed
      if left-interval > 0
        [ assign-colliding-wall left-interval "left wall" ] ]
  ;; top & bottom walls
  let y-speed (speed * dy)
  if y-speed != 0
    [ ;; solve for time it will take particle to reach top wall
      let top-interval (box-edge - 0.5 - ycor - padded-size / 2) / y-speed
      if top-interval > 0
        [ assign-colliding-wall top-interval "top wall" ]
      ;; solve for time it will take particle to reach bottom wall
      let bottom-interval ((- box-edge) + 0.5 - ycor + padded-size / 2) / y-speed
      if bottom-interval > 0
        [ assign-colliding-wall bottom-interval "bottom wall" ] ]
end


to assign-colliding-wall [time-to-collision wall]  ;; particle procedure
  ;; this procedure is used by the check-for-wall-collision procedure
  ;; to assemble the correct particle-wall pair
  ;; time-to-collision is relative (ie, a collision will occur one second from now)
  ;; We need to store the absolute time (ie, a collision will occur at time 48.5 seconds.
  ;; So, we add clock to time-to-collision when we store it.
  let colliding-pair (list (time-to-collision + ticks) self wall)
  set collisions fput colliding-pair collisions
end

to choose-next-collision
  if collisions = [] [ stop ]
  ;; Sort the list of projected collisions between all the particles into an ordered list.
  ;; Take the smallest time-step from the list (which represents the next collision that will
  ;; happen in time).  Use this time step as the tick-delta for all the particles to move through
  let winner first collisions
  foreach collisions [ if first ? < first winner [ set winner ? ] ]
  ;; winner is now the collision that will occur next
  let dt item 0 winner
  ;; If the next collision is more than 1 in the future,
  ;; only advance the simulation one tick, for smoother animation.
  set tick-delta dt - ticks
  if tick-delta > 1
    [ set tick-delta 1
      set particle1 nobody
      set particle2 nobody
      stop ]
  set particle1 item 1 winner
  set particle2 item 2 winner
end


to perform-next-collision
  ;; deal with 3 possible cases:
  ;; 1) no collision at all
  if particle1 = nobody [ stop ]
  ;; 2) particle meets wall
  if is-string? particle2
    [ if particle2 = "left wall" or particle2 = "right wall"
        [ ask particle1 [ set heading (- heading) ]
          stop ]
      if particle2 = "top wall" or particle2 = "bottom wall"
        [ ask particle1 [ set heading 180 - heading ]
          stop ] ]
  ;; 3) particle meets particle
  ask particle1 [ collide-with particle2 ]
end


;;;; Edited to enforce constant speed.
to collide-with [other-particle]  ;; particle procedure
  ;;; PHASE 1: initial setup
  ;; for convenience, grab some quantities from other-particle
  let speed2 [speed] of other-particle
  let heading2 [heading] of other-particle
  let heading1 heading

  set heading heading2

  ask other-particle [
    set heading heading1
  ]

end



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;; particle coloring procedures ;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

to recolor ;; particle procedure
  ;; identify the target for 50 ticks and then again at the end
  ifelse target and (ticks < 50 or ticks > iterations)
  [set color blue]
  [set color white]
end

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;; reporters ;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

to-report init-particle-speed
  report 1
end


to draw-vert-line [ xval ]
  plotxy xval plot-y-min
  plot-pen-down
  plotxy xval plot-y-max
  plot-pen-up
end


; Copyright 2005 Uri Wilensky.
; See Info tab for full copyright and license.
@#$#@#$#@
GRAPHICS-WINDOW
272
10
987
746
70
70
5.0
1
20
1
1
1
0
0
0
1
-70
70
-70
70
1
1
1
ticks
30.0

BUTTON
4
10
97
43
NIL
setup
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

SLIDER
0
45
190
78
initial-number-particles
initial-number-particles
8
16
16
1
1
NIL
HORIZONTAL

BUTTON
98
10
188
43
go
go
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

SLIDER
0
145
190
178
particle-size
particle-size
1
10
10
1
1
NIL
HORIZONTAL

SLIDER
0
80
172
113
number-targets
number-targets
1
8
4
1
1
NIL
HORIZONTAL

SLIDER
0
110
172
143
particle-speed
particle-speed
0.25
2
1
0.25
1
NIL
HORIZONTAL

SLIDER
0
180
172
213
padding-size
padding-size
0
10
0
1
1
NIL
HORIZONTAL

SLIDER
0
270
172
303
iterations
iterations
100
1000
600
50
1
NIL
HORIZONTAL

@#$#@#$#@
## WHAT IS IT?

This model is one in a series of GasLab models. They use the same basic rules for simulating the behavior of gases.  Each model integrates different features in order to highlight different aspects of gas behavior. This model is different from the other GasLab models in that the collision calculations take the circular shape and size of the particles into account, instead of modeling the particles as dimensionless points.

## HOW IT WORKS

The model determines the resulting motion of particles that collide, with no loss in their total momentum or total kinetic energy (an elastic collision).

To calculate the outcome of collision, it is necessary to calculate the exact time at which the edge of one particle (represented as a circle), would touch the edge of another particle (or the walls of a container) if the particles were allowed to continue with their current headings and speeds.

By performing such a calculation, one can determine when the next collision anywhere in the system would occur in time.  From this determination, the model then advances the motion of all the particles using their current headings and speeds that far in time until this next collision point is reached.  Exchange of kinetic energy and momentum between the two particles, according to conservation of kinetic energy and conservation of momentum along the collision axis (a line drawn between the centers of the two particles), is then calculated, and the particles are given new headings and speeds based on this outcome.

## HOW TO USE IT

INITIAL-NUMBER-PARTICLES determines the number of gas particles used with SETUP.  If the world is too small or the particles are too large, the SETUP procedure of the particles will stop so as to prevent overlapping particles.

SMALLEST-PARTICLE-SIZE and LARGEST-PARTICLE-SIZE determines the range of particle sizes that will be created when SETUP is pressed.  (Particles are also assigned a mass proportional to the area of the particle that is created.)

The SETUP button will set the initial conditions.
The GO button will run the simulation.

Monitors:
- % FAST, % MEDIUM, % SLOW: the percentage of particles with different speeds: fast (red), medium (green), and slow (blue).
- AVERAGE SPEED: average speed of the particles.
- AVERAGE ENERGY: average kinetic energy of the particles.

Plots:
- SPEED COUNTS: plots the number of particles in each range of speed.
- SPEED HISTOGRAM: speed distribution of all the particles.  The gray line is the average value, and the black line is the initial average.
- ENERGY HISTOGRAM: distribution of energies of all the particles, calculated as  m*(v^2)/2.  The gray line is the average value, and the black line is the initial average.

Initially, all the particles have the same speed but random directions. Therefore the first histogram plots of speed will show only one column.  If all the particles have the same size (and therefore the same mass), then the first histogram plot of energy will also show one column.  As the particles repeatedly collide, they exchange energy and head off in new directions, and the speeds are dispersed -- some particles get faster, some get slower.  The histogram distribution changes accordingly.

## THINGS TO NOTICE

Run the model with different masses, by setting the MAX-PARTICLE-SIZE larger than the MIN-PARTICLE-SIZE.  Mass is scaled linearly to the area of the particle, so that a particle that is twice the radius of another particle, has four the area and therefore four times the mass.

With many different mass particles colliding over time, different sized particles start to move at different speed ranges (in general).  The smallest mass particles will be usually moving faster (red) than the average particle speed and the largest mass particles will be usually slower (blue) than the average particle speed.  This emergent result is what happens in a gas that is a mixture of particles of different masses.  At any given temperature, the higher mass particles are moving slower (such as Nitrogen gas: N<sub>2</sub>) then the lower mass particles (such as water vapor: H<sub>2</sub>O).

The particle histograms quickly converge on the classic Maxwell-Boltzmann distribution.  What's special about these curves?  Why is the shape of the energy curve not the same as the speed curve?

Look at the other GasLab models to compare the particle histograms.  In those models, the particles are modeled as "point" particles, with an area of "zero".  The results of those models should match this model, even with that simplification.

With particles of different sizes, you may notice some fast moving particles have lower energy than medium speed particles.  How can the difference in the mass of the particles account for this?

## THINGS TO TRY

Setting all the particles to have a very slow speed (e.g. 0.001) and one particle to have a very fast speed helps show how kinetic energy is eventually transferred to all the particles through a series of collisions and would serve as a good model for energy exchange through conduction between hot and cold gases.

To see what the approximate mass of each particle is, type this in the command center:

    ask particles [ set label precision mass 0 ]

## EXTENDING THE MODEL

Collisions between boxes and circles could also be explored.  Variations in size between particles could investigated or variations in the mass of some of the particle could be made to explore other factors that affect the outcome of collisions.

## NETLOGO FEATURES

Instead of advancing one tick at a time as in most models, the tick counter takes on fractional values, using the `tick-advance` primitive.  (In the Interface tab, it is displayed as an integer, but if you make a monitor for `ticks` you'll see the exact value.)

## RELATED MODELS

Look at the other GasLab models to see collisions of "point" particles, that is, the particles are assumed to have an area or volume of zero.

## CREDITS AND REFERENCES

This model was developed as part of the GasLab curriculum (http://ccl.northwestern.edu/curriculum/gaslab/) and has also been incorporated into the Connected Chemistry curriculum (http://ccl.northwestern.edu/curriculum/ConnectedChemistry/)

## HOW TO CITE

If you mention this model or the NetLogo software in a publication, we ask that you include the citations below.

For the model itself:

* Wilensky, U. (2005).  NetLogo GasLab Circular Particles model.  http://ccl.northwestern.edu/netlogo/models/GasLabCircularParticles.  Center for Connected Learning and Computer-Based Modeling, Northwestern University, Evanston, IL.

Please cite the NetLogo software as:

* Wilensky, U. (1999). NetLogo. http://ccl.northwestern.edu/netlogo/. Center for Connected Learning and Computer-Based Modeling, Northwestern University, Evanston, IL.

## COPYRIGHT AND LICENSE

Copyright 2005 Uri Wilensky.

![CC BY-NC-SA 3.0](http://ccl.northwestern.edu/images/creativecommons/byncsa.png)

This work is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 3.0 License.  To view a copy of this license, visit https://creativecommons.org/licenses/by-nc-sa/3.0/ or send a letter to Creative Commons, 559 Nathan Abbott Way, Stanford, California 94305, USA.

Commercial licenses are also available. To inquire about commercial licenses, please contact Uri Wilensky at uri@northwestern.edu.

This model and associated activities and materials were created as part of the project: MODELING ACROSS THE CURRICULUM.  The project gratefully acknowledges the support of the National Science Foundation, the National Institute of Health, and the Department of Education (IERI program) -- grant number REC #0115699.

<!-- 2005 MAC -->
@#$#@#$#@
default
true
0
Polygon -7500403 true true 150 5 40 250 150 205 260 250

airplane
true
0
Polygon -7500403 true true 150 0 135 15 120 60 120 105 15 165 15 195 120 180 135 240 105 270 120 285 150 270 180 285 210 270 165 240 180 180 285 195 285 165 180 105 180 60 165 15

arrow
true
0
Polygon -7500403 true true 150 0 0 150 105 150 105 293 195 293 195 150 300 150

box
false
0
Polygon -7500403 true true 150 285 285 225 285 75 150 135
Polygon -7500403 true true 150 135 15 75 150 15 285 75
Polygon -7500403 true true 15 75 15 225 150 285 150 135
Line -16777216 false 150 285 150 135
Line -16777216 false 150 135 15 75
Line -16777216 false 150 135 285 75

bug
true
0
Circle -7500403 true true 96 182 108
Circle -7500403 true true 110 127 80
Circle -7500403 true true 110 75 80
Line -7500403 true 150 100 80 30
Line -7500403 true 150 100 220 30

butterfly
true
0
Polygon -7500403 true true 150 165 209 199 225 225 225 255 195 270 165 255 150 240
Polygon -7500403 true true 150 165 89 198 75 225 75 255 105 270 135 255 150 240
Polygon -7500403 true true 139 148 100 105 55 90 25 90 10 105 10 135 25 180 40 195 85 194 139 163
Polygon -7500403 true true 162 150 200 105 245 90 275 90 290 105 290 135 275 180 260 195 215 195 162 165
Polygon -16777216 true false 150 255 135 225 120 150 135 120 150 105 165 120 180 150 165 225
Circle -16777216 true false 135 90 30
Line -16777216 false 150 105 195 60
Line -16777216 false 150 105 105 60

car
false
0
Polygon -7500403 true true 300 180 279 164 261 144 240 135 226 132 213 106 203 84 185 63 159 50 135 50 75 60 0 150 0 165 0 225 300 225 300 180
Circle -16777216 true false 180 180 90
Circle -16777216 true false 30 180 90
Polygon -16777216 true false 162 80 132 78 134 135 209 135 194 105 189 96 180 89
Circle -7500403 true true 47 195 58
Circle -7500403 true true 195 195 58

circle
false
0
Circle -7500403 true true 1 1 298

circle 2
false
0
Circle -7500403 true true 0 0 300
Circle -16777216 true false 30 30 240

cow
false
0
Polygon -7500403 true true 200 193 197 249 179 249 177 196 166 187 140 189 93 191 78 179 72 211 49 209 48 181 37 149 25 120 25 89 45 72 103 84 179 75 198 76 252 64 272 81 293 103 285 121 255 121 242 118 224 167
Polygon -7500403 true true 73 210 86 251 62 249 48 208
Polygon -7500403 true true 25 114 16 195 9 204 23 213 25 200 39 123

cylinder
false
0
Circle -7500403 true true 0 0 300

dot
false
0
Circle -7500403 true true 90 90 120

face happy
false
0
Circle -7500403 true true 8 8 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Polygon -16777216 true false 150 255 90 239 62 213 47 191 67 179 90 203 109 218 150 225 192 218 210 203 227 181 251 194 236 217 212 240

face neutral
false
0
Circle -7500403 true true 8 7 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Rectangle -16777216 true false 60 195 240 225

face sad
false
0
Circle -7500403 true true 8 8 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Polygon -16777216 true false 150 168 90 184 62 210 47 232 67 244 90 220 109 205 150 198 192 205 210 220 227 242 251 229 236 206 212 183

fish
false
0
Polygon -1 true false 44 131 21 87 15 86 0 120 15 150 0 180 13 214 20 212 45 166
Polygon -1 true false 135 195 119 235 95 218 76 210 46 204 60 165
Polygon -1 true false 75 45 83 77 71 103 86 114 166 78 135 60
Polygon -7500403 true true 30 136 151 77 226 81 280 119 292 146 292 160 287 170 270 195 195 210 151 212 30 166
Circle -16777216 true false 215 106 30

flag
false
0
Rectangle -7500403 true true 60 15 75 300
Polygon -7500403 true true 90 150 270 90 90 30
Line -7500403 true 75 135 90 135
Line -7500403 true 75 45 90 45

flower
false
0
Polygon -10899396 true false 135 120 165 165 180 210 180 240 150 300 165 300 195 240 195 195 165 135
Circle -7500403 true true 85 132 38
Circle -7500403 true true 130 147 38
Circle -7500403 true true 192 85 38
Circle -7500403 true true 85 40 38
Circle -7500403 true true 177 40 38
Circle -7500403 true true 177 132 38
Circle -7500403 true true 70 85 38
Circle -7500403 true true 130 25 38
Circle -7500403 true true 96 51 108
Circle -16777216 true false 113 68 74
Polygon -10899396 true false 189 233 219 188 249 173 279 188 234 218
Polygon -10899396 true false 180 255 150 210 105 210 75 240 135 240

house
false
0
Rectangle -7500403 true true 45 120 255 285
Rectangle -16777216 true false 120 210 180 285
Polygon -7500403 true true 15 120 150 15 285 120
Line -16777216 false 30 120 270 120

leaf
false
0
Polygon -7500403 true true 150 210 135 195 120 210 60 210 30 195 60 180 60 165 15 135 30 120 15 105 40 104 45 90 60 90 90 105 105 120 120 120 105 60 120 60 135 30 150 15 165 30 180 60 195 60 180 120 195 120 210 105 240 90 255 90 263 104 285 105 270 120 285 135 240 165 240 180 270 195 240 210 180 210 165 195
Polygon -7500403 true true 135 195 135 240 120 255 105 255 105 285 135 285 165 240 165 195

line
true
0
Line -7500403 true 150 0 150 300

line half
true
0
Line -7500403 true 150 0 150 150

padded circle
false
0
Circle -16777216 true false 0 0 300
Circle -7500403 true true 60 60 180

padded circle 2
false
0
Circle -16777216 true false 0 0 300
Circle -7500403 true true 103 103 94

pentagon
false
0
Polygon -7500403 true true 150 15 15 120 60 285 240 285 285 120

person
false
0
Circle -7500403 true true 110 5 80
Polygon -7500403 true true 105 90 120 195 90 285 105 300 135 300 150 225 165 300 195 300 210 285 180 195 195 90
Rectangle -7500403 true true 127 79 172 94
Polygon -7500403 true true 195 90 240 150 225 180 165 105
Polygon -7500403 true true 105 90 60 150 75 180 135 105

plant
false
0
Rectangle -7500403 true true 135 90 165 300
Polygon -7500403 true true 135 255 90 210 45 195 75 255 135 285
Polygon -7500403 true true 165 255 210 210 255 195 225 255 165 285
Polygon -7500403 true true 135 180 90 135 45 120 75 180 135 210
Polygon -7500403 true true 165 180 165 210 225 180 255 120 210 135
Polygon -7500403 true true 135 105 90 60 45 45 75 105 135 135
Polygon -7500403 true true 165 105 165 135 225 105 255 45 210 60
Polygon -7500403 true true 135 90 120 45 150 15 180 45 165 90

square
false
0
Rectangle -7500403 true true 30 30 270 270

square 2
false
0
Rectangle -7500403 true true 30 30 270 270
Rectangle -16777216 true false 60 60 240 240

star
false
0
Polygon -7500403 true true 151 1 185 108 298 108 207 175 242 282 151 216 59 282 94 175 3 108 116 108

target
false
0
Circle -7500403 true true 0 0 300
Circle -16777216 true false 30 30 240
Circle -7500403 true true 60 60 180
Circle -16777216 true false 90 90 120
Circle -7500403 true true 120 120 60

tree
false
0
Circle -7500403 true true 118 3 94
Rectangle -6459832 true false 120 195 180 300
Circle -7500403 true true 65 21 108
Circle -7500403 true true 116 41 127
Circle -7500403 true true 45 90 120
Circle -7500403 true true 104 74 152

triangle
false
0
Polygon -7500403 true true 150 30 15 255 285 255

triangle 2
false
0
Polygon -7500403 true true 150 30 15 255 285 255
Polygon -16777216 true false 151 99 225 223 75 224

truck
false
0
Rectangle -7500403 true true 4 45 195 187
Polygon -7500403 true true 296 193 296 150 259 134 244 104 208 104 207 194
Rectangle -1 true false 195 60 195 105
Polygon -16777216 true false 238 112 252 141 219 141 218 112
Circle -16777216 true false 234 174 42
Rectangle -7500403 true true 181 185 214 194
Circle -16777216 true false 144 174 42
Circle -16777216 true false 24 174 42
Circle -7500403 false true 24 174 42
Circle -7500403 false true 144 174 42
Circle -7500403 false true 234 174 42

turtle
true
0
Polygon -10899396 true false 215 204 240 233 246 254 228 266 215 252 193 210
Polygon -10899396 true false 195 90 225 75 245 75 260 89 269 108 261 124 240 105 225 105 210 105
Polygon -10899396 true false 105 90 75 75 55 75 40 89 31 108 39 124 60 105 75 105 90 105
Polygon -10899396 true false 132 85 134 64 107 51 108 17 150 2 192 18 192 52 169 65 172 87
Polygon -10899396 true false 85 204 60 233 54 254 72 266 85 252 107 210
Polygon -7500403 true true 119 75 179 75 209 101 224 135 220 225 175 261 128 261 81 224 74 135 88 99

vector
true
0
Line -7500403 true 150 15 150 150
Polygon -7500403 true true 120 30 150 0 180 30 120 30

wheel
false
0
Circle -7500403 true true 3 3 294
Circle -16777216 true false 30 30 240
Line -7500403 true 150 285 150 15
Line -7500403 true 15 150 285 150
Circle -7500403 true true 120 120 60
Line -7500403 true 216 40 79 269
Line -7500403 true 40 84 269 221
Line -7500403 true 40 216 269 79
Line -7500403 true 84 40 221 269

x
false
0
Polygon -7500403 true true 270 75 225 30 30 225 75 270
Polygon -7500403 true true 30 75 75 30 270 225 225 270

@#$#@#$#@
NetLogo 5.2.1
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
default
0.0
-0.2 0 0.0 1.0
0.0 1 1.0 0.0
0.2 0 0.0 1.0
link direction
true
0
Line -7500403 true 150 150 90 180
Line -7500403 true 150 150 210 180

@#$#@#$#@
1
@#$#@#$#@
