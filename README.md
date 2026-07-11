# BlockShot Java Prototype

A dependency-free Java/Swing first-person 3D block-world shooter. It is a small
foundation, not a Minecraft mod: it runs as its own desktop game.

## Run

```bash
cd /Users/asishsharma/programming/Llm
mvn compile exec:exec
```

This GPU version uses OpenGL through LWJGL and your MacBook's graphics hardware.

## Controls

- `WASD` — move
- Mouse — look around
- Left click or `Space` — shoot
- `R` — reload
- `Enter` — restart after winning or losing

Eliminate all drones before the timer ends. Drones can shoot back when you are
in range.
