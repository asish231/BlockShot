# BlockShot 3D - Technical Skills Learned

## Project Overview
BlockShot is a first-person 3D block-world game built in Java with two rendering implementations: a pure Java/Swing software renderer and an OpenGL GPU-accelerated version using LWJGL 3.

## Technical Architecture

### 1. **3D Graphics Rendering**

#### Software Renderer (BlockShot.java)
- **Pure Java/Swing Implementation**: No external dependencies
- **Painter's Algorithm**: Z-sorting for depth rendering
  - Faces are sorted by average Z-depth
  - Rendered back-to-front for correct occlusion
- **Camera Transformations**: 
  - Yaw (horizontal rotation) and pitch (vertical tilt)
  - 3D to 2D projection using focal length and near plane
  - Custom `cam()` method transforms world coordinates to camera space
- **Projection Math**:
  ```
  screenX = width/2 + (cameraX / cameraZ) * focalLength
  screenY = height/2 - (cameraY / cameraZ) * focalLength
  ```

#### GPU Renderer (GpuBlockShot.java)
- **OpenGL 2.1 with LWJGL 3.3.6**
- **Fixed-Function Pipeline**: Using legacy OpenGL for simplicity
  - `glFrustum()` for perspective projection
  - `glRotated()` and `glTranslated()` for camera transforms
  - `glLight0` with directional sunlight
- **Depth Testing**: `GL_DEPTH_TEST` for proper occlusion
- **Face Culling**: `GL_CULL_FACE` with back-face culling
- **Lighting**: Phong-style lighting with ambient and diffuse components

### 2. **Procedural World Generation**

#### Terrain System
- **Multi-Octave Noise**: Layered sine/cosine functions for terrain height
  - 6 different frequency layers combined
  - Distance-based height modification for natural valleys near spawn
  - Height clamping to prevent extreme elevations
- **Chunk-Based Loading**:
  - 12x12 block chunks
  - Dynamic loading/unloading based on player position
  - 3-chunk render distance (7x7 chunk grid)
  - HashMap-based chunk management with "chunkX:chunkZ" keys

#### Block Types & Coloring
- **Palette System**: 6 block types (Dirt, Stone, Grass, Wood, Brick, Gold)
- **Procedural Variation**: Random brightness multipliers for natural look
- **Road Generation**: Automatic flattening for cross-shaped road network
- **Props**: 
  - Flowers (3% spawn rate on elevated terrain)
  - Rocks (2% spawn rate on low ground)

### 3. **Game Architecture Patterns**

#### Entity-Component Structure
- **Records for Data**: Java 21 records for immutable data structures
  ```java
  record Box(double x, double y, double z, double w, double h, double d, float r, float g, float b)
  ```
- **Separation of Concerns**:
  - World generation (terrain, structures)
  - Entity management (villagers, structures)
  - Rendering pipeline
  - Input handling

#### Input System
- **GLFW Callbacks**: Event-driven keyboard and mouse handling
- **Mouse Capture**: Cursor hidden and movement tracked for FPS controls
- **Key State Array**: Boolean array for simultaneous key detection (WASD movement)

### 4. **First-Person Movement**

#### Physics
- **Terrain Following**: Player Y position calculated from terrain height
- **Speed Modulation**: Sprint mode (Shift key) doubles movement speed
- **Direction Vectors**:
  ```java
  forward = (sin(yaw), cos(yaw))
  strafe = (cos(yaw), -sin(yaw))
  ```
- **Pitch/Yaw System**: Independent rotation axes for natural camera control

### 5. **GUI & HUD System**

#### Custom Font Rendering
- **Bitmap Font**: Generated from Java AWT Font to OpenGL texture
- **Character Atlas**: 512x512 texture with pre-calculated glyph positions
- **Metrics Tracking**: Character width, height, and advance for proper spacing
- **Orthographic Overlay**: 2D UI rendered over 3D scene

#### HUD Elements
- **Panel System**: Semi-transparent rounded rectangles
- **Sun Indicator**: Directional indicator with gradient
- **Block Palette**: Visual selector for building materials (1-6 keys)
- **Crosshair**: Multi-line center indicator

### 6. **Building Mechanics**

#### Block Placement
- **Ray-Cast Projection**: 
  - Direction calculated from yaw/pitch
  - 3-unit distance from player
  - Rounded to block grid coordinates
- **Collision Detection**: 
  - Prevents placing blocks too close to player
  - Checks for existing blocks at target position
- **Material Selection**: 6 block types with keyboard shortcuts

### 7. **macOS-Specific Considerations**

#### LWJGL on macOS
- **`-XstartOnFirstThread`**: Required JVM argument for GLFW on macOS
- **ARM64 Natives**: Platform-specific native libraries
  ```xml
  <classifier>natives-macos-arm64</classifier>
  ```
- **Java 21 Warnings**: Handled restricted method access warnings for native loading

### 8. **Maven Build System**

#### Project Structure
```
pom.xml
├── LWJGL dependencies (core, glfw, opengl)
├── ARM64 native classifiers
├── JUnit 5 for testing
└── Exec plugin with custom JVM args
```

#### Execution
- Exec plugin configured with `-XstartOnFirstThread`
- Classpath management for native libraries
- Java 21 compiler target

### 9. **Modular Design Lessons**

#### Feature Removal (Combat → Exploration)
Successfully removed combat system by:
- **Data Model Changes**: Removed `Drone`, `Car` classes, health/ammo fields
- **Update Loop Simplification**: Cleaned enemy AI and damage logic
- **Rendering Pipeline**: Removed `drawDrone()`, `drawCar()` methods
- **Input Handlers**: Disabled shoot/reload key bindings
- **HUD Updates**: Replaced combat stats with exploration-focused display

This demonstrates:
- Clean separation of concerns
- Minimal coupling between systems
- Easy feature toggles for different game modes

### 10. **Performance Optimizations**

#### Rendering
- **Frustum Culling**: Only render chunks near player
- **Chunk Caching**: Reuse terrain data when chunks remain loaded
- **Fixed-Function Pipeline**: Trade flexibility for performance on older hardware
- **V-Sync**: `glfwSwapInterval(1)` prevents screen tearing

#### Memory Management
- **Chunk Unloading**: (Can be implemented) Remove far chunks to limit memory
- **Draw Call Batching**: All blocks rendered with same shader/material batched

## Key Takeaways

1. **3D Math Fundamentals**: Understanding projection matrices, camera transforms, and coordinate spaces
2. **OpenGL Fixed Pipeline**: Working with legacy OpenGL for rapid prototyping
3. **Procedural Generation**: Noise functions and chunk-based streaming for infinite worlds
4. **Event-Driven Input**: GLFW callback system for responsive controls
5. **Platform-Specific Quirks**: Handling macOS threading requirements with LWJGL
6. **Modular Architecture**: Clean code structure enables easy feature modification
7. **Java Records**: Modern Java features for cleaner data structures
8. **Maven Dependency Management**: Platform-specific native libraries and classifiers

## Technologies Used

- **Java 21**: Records, pattern matching, modern language features
- **LWJGL 3.3.6**: Lightweight Java Game Library for OpenGL bindings
- **OpenGL 2.1**: Fixed-function graphics pipeline
- **GLFW**: Cross-platform window and input handling
- **Java AWT**: Font rendering for texture generation
- **Maven**: Build automation and dependency management

## Potential Enhancements

- Add proper chunk unloading for memory efficiency
- Implement save/load system for world persistence
- Add more biome types with different block palettes
- Improve lighting with dynamic shadows
- Add weather effects (rain, clouds)
- Multiplayer support with client-server architecture
- Modern OpenGL (3.3+) with shaders for better performance
- Texture mapping instead of flat colors
- Skybox for immersive environment
