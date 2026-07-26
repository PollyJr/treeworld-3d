# TreeWorld 3D (Debug)

High-end 3D endless runner for Android, built on a **custom mini 3D engine** (pure OpenGL ES 2.0, no Unity/Godot).

## Engine features
- Custom GLSL shader pipeline: hemisphere lighting + specular + distance fog
- Gradient sky dome with sun disc and twinkling stars
- Full day/night cycle (day -> sunset -> night -> dawn) driven by run distance
- CPU particle system (falling leaves, fireflies, dust trails, sparkle bursts, crash debris)
- Blob shadows, alpha blending, camera shake, procedural low-poly meshes

## Gameplay
- Swipe left/right to switch lanes, swipe up or tap to jump, swipe down to slide
- Obstacles: rocks (jump), fallen logs (jump), hanging branches (slide under)
- Coins with combo multiplier (up to x5), bonus orbs
- Power-ups: Shield (survive one hit), Magnet (attract coins, 8s), 2x Score (10s)
- Speed ramps from 9 to 28 m/s; milestone jingles every 250 m
- Birds fly past by day, fireflies come out at night

## Audio
- Fully synthesized soundtrack, zero assets: wind ambience, birdsong by day, crickets by night
- SFX: jump, slide, coin, orb, power-up, shield hit, crash, milestone

## Debug build
- On-screen debug HUD: FPS, speed, day phase, build tag
- Installs as `com.pollyjr.treegame.debug` alongside any release build

## Build
Requires Android SDK 34 + JDK 17:
```
gradle assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`
