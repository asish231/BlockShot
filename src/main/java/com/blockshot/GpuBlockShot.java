package com.blockshot;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import com.blockshot.combat.WeaponState;
import com.blockshot.combat.WeaponType;
import com.blockshot.entity.Fish;
import com.blockshot.entity.MouseLookController;
import com.blockshot.entity.Npc;
import com.blockshot.entity.NpcRole;
import com.blockshot.entity.Player;
import com.blockshot.entity.Vehicle;
import com.blockshot.entity.VehicleControl;
import com.blockshot.entity.VehicleType;
import com.blockshot.entity.Villager;
import com.blockshot.game.CrimeSystem;
import com.blockshot.game.CrimeType;
import com.blockshot.game.GameLaunchOptions;
import com.blockshot.game.WorldInteractionSystem;
import com.blockshot.input.InputCaptureState;
import com.blockshot.net.MultiplayerClient;
import com.blockshot.net.MultiplayerServer;
import com.blockshot.net.NetworkMessage;
import com.blockshot.physics.FallingBlockEntity;
import com.blockshot.render.ChunkRenderer;
import com.blockshot.render.EntityRenderer;
import com.blockshot.render.HudRenderer;
import com.blockshot.render.HudState;
import com.blockshot.render.ViewFrustum;
import com.blockshot.world.BlockInventory;
import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import com.blockshot.world.Box;
import com.blockshot.world.Chunk;
import com.blockshot.world.ChunkManager;
import com.blockshot.world.TerrainGenerator;
import com.blockshot.world.VoxelRaycaster;
import com.blockshot.world.WorldLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

/**
 * Entry point and game loop for the GPU (LWJGL/OpenGL) edition.
 *
 * <p>This class is a thin coordinator. The heavy lifting lives in dedicated,
 * unit-tested modules that it wires together:
 * <ul>
 *   <li>{@link ChunkManager} streams an effectively infinite, edit-aware voxel world.</li>
 *   <li>{@link ChunkRenderer} keeps one GPU mesh per chunk (hidden-face + frustum culled),
 *       replacing the old per-block immediate mode that made the world stutter.</li>
 *   <li>{@link EntityRenderer} draws the small moving things: people, police, vehicles,
 *       fish, falling debris and remote players.</li>
 *   <li>{@link WorldInteractionSystem} turns block placement/removal into inventory changes,
 *       structural collapse and falling blocks that can crush people and cars.</li>
 *   <li>{@link CrimeSystem} + {@link Npc} give a GTA-style wanted level with fleeing
 *       civilians and pursuing police.</li>
 *   <li>{@link MultiplayerServer}/{@link MultiplayerClient} add optional LAN play.</li>
 * </ul>
 */
public final class GpuBlockShot {

    private static final int RENDER_DISTANCE = 6;
    private static final int LOAD_BUDGET = 2;      // chunks generated per frame while roaming
    private static final int SPAWN_LOAD = 49;      // chunks generated up front so spawn is populated
    private static final double REACH = 6.0;
    private static final double WEAPON_RANGE = 90.0;
    private static final double MOUSE_SENSITIVITY = 0.12;
    private static final double VERTICAL_FOV = 70.0;

    /** Every material the player can select, collect and build with. */
    private static final BlockMaterial[] BUILDABLE = {
        BlockMaterial.DIRT, BlockMaterial.STONE, BlockMaterial.GRASS, BlockMaterial.WOOD,
        BlockMaterial.BRICK, BlockMaterial.GOLD, BlockMaterial.GLASS, BlockMaterial.CONCRETE,
        BlockMaterial.STEEL, BlockMaterial.SAND,
    };

    private long window;

    private final GameLaunchOptions options;
    private final long seed;
    private final TerrainGenerator terrain;
    private final ChunkManager chunks;
    private final BlockInventory inventory = new BlockInventory();
    private final WorldInteractionSystem interactions;
    private final CrimeSystem crime = new CrimeSystem();
    private final MouseLookController look = new MouseLookController(MOUSE_SENSITIVITY);
    private final EntityRenderer entities = new EntityRenderer();
    private final UUID playerId = UUID.randomUUID();

    private final WeaponState[] weapons = {
        new WeaponState(WeaponType.PISTOL),
        new WeaponState(WeaponType.RIFLE),
        new WeaponState(WeaponType.SHOTGUN),
    };
    private int weaponIndex;

    private final List<Vehicle> vehicles = new ArrayList<>();
    private Vehicle ridden;

    private final boolean[] keys = new boolean[400];
    private final InputCaptureState capture = new InputCaptureState(false);
    private boolean firing;

    private boolean fullscreen;
    private final int[] savedX = new int[1];
    private final int[] savedY = new int[1];
    private final int[] savedW = new int[1];
    private final int[] savedH = new int[1];

    private int selectedBlock;
    private boolean combatMode;    // false = BUILD, true = COMBAT (on foot)
    private int health = 100;
    private double damageCooldown;
    private String message = "";
    private double messageTimer;

    private final float[][] palette = new float[BUILDABLE.length][];
    private final String[] blockNames = new String[BUILDABLE.length];

    private Player player;
    private double spawnX;
    private double spawnZ;
    private ChunkRenderer chunkRenderer;
    private HudRenderer hud;

    private MultiplayerServer server;
    private MultiplayerClient client;

    private GpuBlockShot(GameLaunchOptions options) {
        this.options = options;
        this.seed = options.worldSeed();
        this.terrain = new TerrainGenerator(seed);
        this.chunks = new ChunkManager(terrain, RENDER_DISTANCE);
        this.interactions = new WorldInteractionSystem(chunks, inventory);
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        GameLaunchOptions options;
        try {
            options = GameLaunchOptions.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Invalid launch options: " + ex.getMessage());
            System.err.println("Usage: [--host[=port]] [--join=host:port] [--name=Player] [--seed=number]");
            return;
        }
        new GpuBlockShot(options).run();
    }

    void run() {
        for (int i = 0; i < BUILDABLE.length; i++) {
            palette[i] = new float[] {BUILDABLE[i].r(), BUILDABLE[i].g(), BUILDABLE[i].b()};
            blockNames[i] = BUILDABLE[i].name();
            inventory.add(BUILDABLE[i], 128);
        }

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(1100, 700, "BlockShot 3D — Open World", 0, 0);
        if (window == 0) throw new IllegalStateException("Window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        hud = new HudRenderer();
        hud.init();
        chunkRenderer = new ChunkRenderer();
        setupInput();
        setupGl();
        startNetworking();
        spawnWorld();

        double last = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            double now = glfwGetTime();
            double dt = Math.min(0.05, now - last);
            last = now;
            update(dt);
            draw();
            glfwSwapBuffers(window);
        }
        shutdown();
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    void setupInput() {
        glfwSetKeyCallback(window, (w, key, sc, action, mods) -> {
            if (key >= 0 && key < keys.length) keys[key] = action != GLFW_RELEASE;
            if (action != GLFW_PRESS) return;
            if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(window, true);
            } else if (key == GLFW_KEY_F11) {
                toggleFullscreen();
            } else if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                int idx = key - GLFW_KEY_1;
                if (idx < BUILDABLE.length) selectedBlock = idx;
            } else if (key == GLFW_KEY_B) {
                combatMode = !combatMode;
                setMessage(combatMode ? "COMBAT MODE" : "BUILD MODE");
            } else if (key == GLFW_KEY_Q && combatMode) {
                cycleWeapon();
            } else if (key == GLFW_KEY_R && combatMode) {
                setMessage(weapon().reload() ? "RELOADED" : "NO RESERVE AMMO");
            } else if (key == GLFW_KEY_F) {
                toggleVehicle();
            }
        });
        glfwSetCursorPosCallback(window, (w, mx, my) -> {
            if (capture.captured()) look.onPointer(mx, my);
        });
        glfwSetMouseButtonCallback(window, (w, btn, act, mods) -> {
            if (act == GLFW_PRESS && capture.onPressShouldRecapture()) {
                applyCursorMode();
                look.reset();
                return;
            }
            if (btn == GLFW_MOUSE_BUTTON_LEFT) {
                firing = act != GLFW_RELEASE;
                if (act == GLFW_PRESS && !combatMode && ridden == null) buildAction(false);
            } else if (btn == GLFW_MOUSE_BUTTON_RIGHT && act == GLFW_PRESS
                    && !combatMode && ridden == null) {
                buildAction(true);
            }
        });
        glfwSetScrollCallback(window, (w, xoff, yoff) -> {
            int n = BUILDABLE.length;
            selectedBlock = ((selectedBlock + (yoff > 0 ? -1 : 1)) % n + n) % n;
        });
        glfwSetWindowFocusCallback(window, (w, focused) -> {
            capture.onFocusChanged(focused);
            applyCursorMode();
            look.reset();
        });
        applyCursorMode();
    }

    void setupGl() {
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_COLOR_MATERIAL);
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_NORMALIZE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }


    void centerWindow() {
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor == 0 ? null : glfwGetVideoMode(monitor);
        if (mode == null) return;
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetWindowSize(window, w, h);
        glfwSetWindowPos(window, Math.max(0, (mode.width() - w[0]) / 2),
                Math.max(0, (mode.height() - h[0]) / 2));
    }

    /** Grab or free the OS cursor to match the current capture state. */
    void applyCursorMode() {
        boolean grab = capture.captured();
        glfwSetInputMode(window, GLFW_CURSOR, grab ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (grab && glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }
    }

    /**
     * Toggle borderless fullscreen on the primary monitor. Besides being nicer to
     * play, a fullscreen window reliably becomes the active window on macOS, so this
     * doubles as a dependable way to recover focus if the game ever feels stuck.
     */
    void toggleFullscreen() {
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor == 0 ? null : glfwGetVideoMode(monitor);
        if (mode == null) return;
        fullscreen = !fullscreen;
        if (fullscreen) {
            glfwGetWindowPos(window, savedX, savedY);
            glfwGetWindowSize(window, savedW, savedH);
            glfwSetWindowMonitor(window, monitor, 0, 0,
                    mode.width(), mode.height(), mode.refreshRate());
            setMessage("FULLSCREEN (F11 TO EXIT)");
        } else {
            glfwSetWindowMonitor(window, 0, savedX[0], savedY[0],
                    Math.max(640, savedW[0]), Math.max(480, savedH[0]), 0);
            setMessage("WINDOWED");
        }
        glfwSwapInterval(1);
        glfwFocusWindow(window);
        capture.onFocusChanged(true);
        applyCursorMode();
        look.reset();
    }

    void startNetworking() {
        if (!options.multiplayer()) return;
        try {
            String host;
            int port;
            if (options.hosting()) {
                server = new MultiplayerServer(options.port());
                server.start();
                host = "127.0.0.1";
                port = server.port();
            } else {
                host = options.remoteHost();
                port = options.port();
            }
            client = new MultiplayerClient(playerId, options.playerName(), host, port);
            client.start();
            setMessage(options.hosting() ? "HOSTING ON PORT " + port : "JOINED " + host + ":" + port);
        } catch (RuntimeException ex) {
            System.err.println("Multiplayer unavailable: " + ex.getMessage());
            closeNetworking();
            setMessage("MULTIPLAYER UNAVAILABLE - OFFLINE");
        }
    }

    void spawnWorld() {
        if (chunks.usesOsm()) {
            // The selected GPS reference is defined to be voxel-space origin.
            spawnX = 0.5;
            spawnZ = 0.5;
        } else {
            // Spawn on the plaza row just south of the home city's landmark chunk;
            // that row is kept clear of structures by every district type.
            WorldLayout.City home = chunks.layout().nearestCity(0, 0);
            spawnX = home.chunkX() * Chunk.SIZE + 7.5;
            spawnZ = (home.chunkZ() + 1) * Chunk.SIZE + 0.5;
        }
        chunks.updateIncremental(spawnX, spawnZ, SPAWN_LOAD);
        player = new Player(spawnX, chunks.surfaceY(spawnX, spawnZ), spawnZ);
        player.pitch = 5;
        spawnVehicles();
    }

    void spawnVehicles() {
        if (chunks.usesOsm()) {
            addVehicle(VehicleType.CAR, spawnX + 3.0, spawnZ);
            addVehicle(VehicleType.CAR, spawnX + 6.0, spawnZ);
            addVehicle(VehicleType.HELICOPTER, spawnX, spawnZ + 6.0);
            addVehicle(VehicleType.PLANE, spawnX, spawnZ + 10.0);
            return;
        }
        WorldLayout.City home = chunks.layout().nearestCity(0, 0);
        double rowZ = (home.chunkZ() + 1) * Chunk.SIZE + 0.5;
        double baseX = home.chunkX() * Chunk.SIZE;
        addVehicle(VehicleType.CAR, baseX + 4.5, rowZ);
        addVehicle(VehicleType.CAR, baseX + 11.5, rowZ);
        addVehicle(VehicleType.HELICOPTER, baseX + 13.5, rowZ);
        addVehicle(VehicleType.PLANE, baseX + 1.5, rowZ);
    }

    void addVehicle(VehicleType type, double x, double z) {
        vehicles.add(new Vehicle(UUID.randomUUID(), type, x, chunks.surfaceY(x, z), z));
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    void update(double dt) {
        applyLook();
        weapon().update(dt);
        if (damageCooldown > 0) damageCooldown -= dt;
        if (messageTimer > 0) messageTimer -= dt;

        applyRemoteEdits();

        if (ridden != null) updateDriving(dt);
        else updateOnFoot(dt);

        chunks.updateIncremental(player.x, player.z, LOAD_BUDGET);
        chunkRenderer.sync(chunks);

        int wanted = crime.wantedLevel();
        for (Chunk c : chunks.loadedChunks()) {
            for (Villager v : c.villagers) v.update(dt, chunks);
            for (Fish f : c.fish) f.update(dt);
            for (Npc npc : c.npcs) {
                if (!npc.alive()) continue;
                npc.update(dt, chunks, player.x, player.z, wanted);
                if (npc.role() == NpcRole.POLICE && wanted > 0 && damageCooldown <= 0
                        && Math.hypot(npc.x - player.x, npc.z - player.z) < 2.2) {
                    hurtPlayer(12);
                }
            }
        }

        for (Vehicle v : vehicles) {
            if (v != ridden) v.update(dt, playerId, new VehicleControl(0, 0, 0, 0), chunks);
        }

        applyImpacts(interactions.updateFalling(dt));

        if (firing && combatMode && ridden == null) fireWeapon();

        crime.update(dt);
        regenerateHealth(dt);
        sendNetworkState();
    }

    void applyLook() {
        player.yaw += look.consumeYawDelta();
        player.pitch = Math.max(-89, Math.min(89, player.pitch + look.consumePitchDelta()));
    }

    void updateOnFoot(double dt) {
        double forward = (keys[GLFW_KEY_W] ? 1 : 0) - (keys[GLFW_KEY_S] ? 1 : 0);
        double strafe = (keys[GLFW_KEY_A] ? 1 : 0) - (keys[GLFW_KEY_D] ? 1 : 0);
        boolean jump = keys[GLFW_KEY_SPACE];
        boolean sprint = keys[GLFW_KEY_LEFT_SHIFT];
        player.update(dt, forward, strafe, jump, sprint, chunks);
    }

    void updateDriving(double dt) {
        double throttle = (keys[GLFW_KEY_W] ? 1 : 0) - (keys[GLFW_KEY_S] ? 1 : 0);
        double steering = (keys[GLFW_KEY_D] ? 1 : 0) - (keys[GLFW_KEY_A] ? 1 : 0);
        double lift = (keys[GLFW_KEY_SPACE] ? 1 : 0) - (keys[GLFW_KEY_LEFT_SHIFT] ? 1 : 0);
        double pitch = (keys[GLFW_KEY_LEFT_SHIFT] ? 1 : 0) - (keys[GLFW_KEY_SPACE] ? 1 : 0);
        ridden.update(dt, playerId, new VehicleControl(throttle, steering, lift, pitch), chunks);
        player.x = ridden.x;
        player.z = ridden.z;
        player.y = ridden.y;
        player.vy = 0;
        player.onGround = true;
        runOverCheck();
    }

    void runOverCheck() {
        if (ridden.type != VehicleType.CAR || Math.abs(ridden.speed) < 4) return;
        for (Chunk c : chunks.loadedChunks()) {
            for (Npc npc : c.npcs) {
                if (!npc.alive()) continue;
                if (Math.hypot(npc.x - ridden.x, npc.z - ridden.z) < 1.8) {
                    if (npc.damage(60)) setMessage("HIT AND RUN");
                    crime.report(CrimeType.ASSAULT);
                    witnessCrime(npc.x, npc.z);
                }
            }
        }
    }

    void applyImpacts(List<WorldInteractionSystem.Impact> impacts) {
        for (WorldInteractionSystem.Impact im : impacts) {
            if (im.damage() <= 0) continue;
            for (Chunk c : chunks.loadedChunks()) {
                for (Npc npc : c.npcs) {
                    if (npc.alive() && dist3(npc.x, npc.y + 1, npc.z, im.x(), im.y(), im.z()) < 1.6) {
                        npc.damage(im.damage());
                    }
                }
            }
            if (dist3(player.x, player.y + 1, player.z, im.x(), im.y(), im.z()) < 1.6) {
                hurtPlayer(im.damage());
                setMessage("CRUSHED BY DEBRIS");
            }
        }
    }

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------

    void fireWeapon() {
        WeaponState w = weapon();
        if (!w.tryFire()) {
            if (w.ammo() == 0 && messageTimer <= 0) setMessage("OUT OF AMMO - PRESS R");
            return;
        }
        double[] d = viewDir();
        double eyeX = player.x;
        double eyeY = player.eyeY();
        double eyeZ = player.z;
        Optional<VoxelRaycaster.Hit> block = VoxelRaycaster.cast(
                eyeX, eyeY, eyeZ, d[0], d[1], d[2], WEAPON_RANGE, chunks::blockAt);
        double maxDist = block.map(VoxelRaycaster.Hit::distance).orElse(WEAPON_RANGE);

        Npc target = null;
        com.blockshot.net.NetworkMessage.PlayerState targetPlayer = null;
        double best = maxDist;

        // Check remote players first
        if (client != null) {
            for (com.blockshot.net.NetworkMessage.PlayerState rp : client.remotePlayers().values()) {
                double ox = rp.x() - eyeX;
                double oy = (rp.y() + 1.0) - eyeY;
                double oz = rp.z() - eyeZ;
                double t = ox * d[0] + oy * d[1] + oz * d[2];
                if (t < 0 || t > best) continue;
                double perp2 = (ox * ox + oy * oy + oz * oz) - t * t;
                if (perp2 <= 0.55 * 0.55) {
                    best = t;
                    targetPlayer = rp;
                    target = null;
                }
            }
        }

        // Check NPCs
        for (Chunk c : chunks.loadedChunks()) {
            for (Npc npc : c.npcs) {
                if (!npc.alive()) continue;
                double ox = npc.x - eyeX;
                double oy = (npc.y + 1.0) - eyeY;
                double oz = npc.z - eyeZ;
                double t = ox * d[0] + oy * d[1] + oz * d[2];
                if (t < 0 || t > best) continue;
                double perp2 = (ox * ox + oy * oy + oz * oz) - t * t;
                if (perp2 <= 0.55 * 0.55) {
                    best = t;
                    target = npc;
                    targetPlayer = null;
                }
            }
        }

        if (targetPlayer != null) {
            setMessage("HIT PLAYER: " + targetPlayer.name());
        } else if (target != null) {
            boolean killed = target.damage(weaponDamage(w.type()));
            crime.report(target.role() == NpcRole.POLICE
                    ? CrimeType.ATTACK_POLICE : CrimeType.ASSAULT);
            witnessCrime(target.x, target.z);
            setMessage(killed ? "TARGET DOWN" : "HIT");
        }
    }

    void witnessCrime(double x, double z) {
        for (Chunk c : chunks.loadedChunks()) {
            for (Npc npc : c.npcs) {
                if (npc.role() == NpcRole.CIVILIAN && npc.alive()
                        && Math.hypot(npc.x - x, npc.z - z) < 22) {
                    npc.witnessCrime(x, z);
                }
            }
        }
    }

    void cycleWeapon() {
        weaponIndex = (weaponIndex + 1) % weapons.length;
        setMessage("WEAPON " + weapon().type());
    }

    WeaponState weapon() {
        return weapons[weaponIndex];
    }

    static int weaponDamage(WeaponType type) {
        return switch (type) {
            case PISTOL -> 34;
            case RIFLE -> 26;
            case SHOTGUN -> 62;
        };
    }

    // ------------------------------------------------------------------
    // Vehicles
    // ------------------------------------------------------------------

    void toggleVehicle() {
        if (ridden != null) {
            ridden.disembark(playerId);
            double ex = ridden.x + 2.0;
            double ez = ridden.z;
            player.x = ex;
            player.z = ez;
            player.y = chunks.surfaceY(ex, ez);
            player.vy = 0;
            setMessage("EXITED " + ridden.type);
            ridden = null;
            return;
        }
        Vehicle nearest = null;
        double bestDistance = 4.0;
        for (Vehicle v : vehicles) {
            double d = Math.hypot(v.x - player.x, v.z - player.z);
            if (d < bestDistance) {
                bestDistance = d;
                nearest = v;
            }
        }
        if (nearest != null && nearest.board(playerId)) {
            ridden = nearest;
            setMessage("ENTERED " + nearest.type + "  (WASD DRIVE, SPACE UP, F EXIT)");
        }
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    void buildAction(boolean place) {
        double[] d = viewDir();
        Optional<VoxelRaycaster.Hit> hit = VoxelRaycaster.cast(
                player.x, player.eyeY(), player.z, d[0], d[1], d[2], REACH, chunks::blockAt);
        if (hit.isEmpty()) return;
        VoxelRaycaster.Hit h = hit.get();
        if (place) {
            BlockPos target = h.adjacent();
            if (target == null) return;
            BlockMaterial material = BUILDABLE[selectedBlock];
            if (interactions.placeBlock(target, material, this::occupiedByEntity)) {
                sendEdit(target, material, true);
            } else {
                setMessage(inventory.count(material) == 0 ? "OUT OF " + material.name() : "BLOCKED");
            }
        } else {
            WorldInteractionSystem.BreakResult result = interactions.breakBlock(h.block());
            if (result.changed()) {
                sendEdit(h.block(), result.material(), false);
                setMessage("COLLECTED " + result.material().name());
            }
        }
    }

    boolean occupiedByEntity(BlockPos pos) {
        int fx = (int) Math.floor(player.x);
        int fz = (int) Math.floor(player.z);
        int fy = (int) Math.floor(player.y);
        if (pos.x() == fx && pos.z() == fz && (pos.y() == fy || pos.y() == fy + 1)) {
            return true;
        }
        for (Chunk c : chunks.loadedChunks()) {
            for (Npc npc : c.npcs) {
                if (!npc.alive()) continue;
                int nx = (int) Math.floor(npc.x);
                int nz = (int) Math.floor(npc.z);
                int ny = (int) Math.floor(npc.y);
                if (pos.x() == nx && pos.z() == nz && (pos.y() == ny || pos.y() == ny + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Health & networking
    // ------------------------------------------------------------------

    void hurtPlayer(int amount) {
        health = Math.max(0, health - amount);
        damageCooldown = 0.6;
        if (health == 0) respawn();
    }

    void respawn() {
        if (ridden != null) {
            ridden.disembark(playerId);
            ridden = null;
        }
        player.x = spawnX;
        player.z = spawnZ;
        player.y = chunks.surfaceY(spawnX, spawnZ);
        player.vy = 0;
        health = 100;
        setMessage("RESPAWNED");
    }

    void regenerateHealth(double dt) {
        if (crime.wantedLevel() == 0 && damageCooldown <= 0 && health < 100) {
            health = Math.min(100, health + (int) Math.ceil(dt * 6));
        }
    }

    void applyRemoteEdits() {
        if (client == null) return;
        for (NetworkMessage.BlockEdit e : client.drainBlockEdits()) {
            try {
                chunks.applyReplicatedEdit(e.pos(), e.material(), e.placed());
            } catch (RuntimeException ignored) {
                // Ignore malformed replicated edits rather than crash the loop.
            }
        }
    }

    void sendNetworkState() {
        if (client == null || !client.connected()) return;
        try {
            client.sendPlayerState(player.x, player.eyeY(), player.z, player.yaw);
        } catch (RuntimeException ignored) {
            // A dropped state packet is harmless; the next frame retries.
        }
    }

    void sendEdit(BlockPos pos, BlockMaterial material, boolean placed) {
        if (client == null || !client.connected() || material == null) return;
        try {
            client.sendBlockEdit(pos, material, placed);
        } catch (RuntimeException ignored) {
            // Best-effort replication.
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    void draw() {
        int[] ww = new int[1];
        int[] hh = new int[1];
        glfwGetFramebufferSize(window, ww, hh);
        int width = ww[0];
        int height = hh[0];
        glViewport(0, 0, width, height);
        glClearColor(0.47f, 0.72f, 0.92f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        double aspect = height == 0 ? 1 : (double) width / height;
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        double near = 0.1;
        double far = 260;
        double topTan = Math.tan(Math.toRadians(VERTICAL_FOV) / 2) * near;
        glFrustum(-topTan * aspect, topTan * aspect, -topTan, topTan, near, far);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glRotated(player.pitch, 1, 0, 0);
        glRotated(player.yaw, 0, 1, 0);
        glTranslated(-player.x, -player.eyeY(), -player.z);

        float[] sunPos = {-0.4f, 1.3f, 0.3f, 0};
        glLightModelfv(GL_LIGHT_MODEL_AMBIENT, new float[] {0.32f, 0.34f, 0.38f, 1});
        glLightfv(GL_LIGHT0, GL_POSITION, sunPos);
        glLightfv(GL_LIGHT0, GL_DIFFUSE, new float[] {1f, 1f, 0.94f, 1});

        ViewFrustum frustum = new ViewFrustum(player.x, player.z, player.yaw,
                VERTICAL_FOV, aspect, 240);

        // Opaque world: one GPU draw per visible chunk, hidden faces already removed.
        chunkRenderer.drawOpaque(frustum);

        // Decorative props and living things use immediate mode; they are few and moving.
        for (Chunk c : chunks.loadedChunks()) {
            if (!frustum.intersectsChunk(c.cx, c.cz)) continue;
            for (Box b : c.opaqueBlocks) entities.drawBox(b);
            for (Villager v : c.villagers) entities.drawVillager(v);
            for (Npc npc : c.npcs) entities.drawNpc(npc);
        }
        for (Vehicle v : vehicles) {
            if (v != ridden) entities.drawVehicle(v);
        }
        for (FallingBlockEntity fb : interactions.fallingBlocks()) entities.drawFallingBlock(fb);
        if (client != null) {
            for (NetworkMessage.PlayerState state : client.remotePlayers().values()) {
                entities.drawRemotePlayer(state);
            }
        }

        // Translucent water and fish: blend without writing depth.
        glDepthMask(false);
        chunkRenderer.drawTranslucent(frustum, player.x, player.z);
        for (Chunk c : chunks.loadedChunks()) {
            if (!frustum.intersectsChunk(c.cx, c.cz)) continue;
            for (Fish fi : c.fish) entities.drawFish(fi);
        }
        glDepthMask(true);

        if (ridden == null) entities.drawHeldItem(BUILDABLE[selectedBlock], combatMode);

        hud.render(width, height, palette, blockNames, selectedBlock, player, chunks, hudState());
    }

    HudState hudState() {
        WeaponState w = weapon();
        String mode = ridden != null ? "DRIVE" : (combatMode ? "COMBAT" : "BUILD");
        String vehicleName = ridden != null ? ridden.type.name() : "ON FOOT";
        int occupants = ridden != null ? ridden.occupantCount() : 0;
        int capacity = ridden != null ? ridden.type.capacity() : 0;
        int remotePlayers = client != null ? client.remotePlayers().size() : 0;
        String network = options.hosting() ? "HOST" : options.joining() ? "JOIN" : "OFFLINE";
        int draws = chunkRenderer.opaqueDrawCount() + chunkRenderer.translucentDrawCount();
        String shown = capture.captured()
                ? (messageTimer > 0 ? message : "")
                : "CLICK WINDOW TO CAPTURE MOUSE";
        return new HudState(health, crime.wantedLevel(), mode, w.type().name(), w.ammo(),
                w.reserve(), inventory.count(BUILDABLE[selectedBlock]), vehicleName, occupants,
                capacity, remotePlayers, network, chunkRenderer.gpuBytes(), draws, shown);
    }

    // ------------------------------------------------------------------
    // Helpers & shutdown
    // ------------------------------------------------------------------

    double[] viewDir() {
        double yr = Math.toRadians(player.yaw);
        double pr = Math.toRadians(player.pitch);
        return new double[] {
            Math.sin(yr) * Math.cos(pr),
            -Math.sin(pr),
            -Math.cos(yr) * Math.cos(pr),
        };
    }

    void setMessage(String text) {
        message = text;
        messageTimer = 3.0;
    }

    static double dist3(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    void shutdown() {
        closeNetworking();
        if (chunkRenderer != null) chunkRenderer.close();
        if (hud != null) hud.close();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    void closeNetworking() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }
}
