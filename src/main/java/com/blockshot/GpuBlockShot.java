package com.blockshot;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.List;

public final class GpuBlockShot {
    long window;
    double x = 0, y = 1.7, z = 0, yaw = 0, pitch = 0;
    boolean[] keys = new boolean[400];
    boolean mouseCaptured = false;
    int score = 0;
    // Exploration mode - no combat

    int selectedBlock = 0;
    float[][] blockPalette = {
        {0.60f, 0.40f, 0.20f},
        {0.50f, 0.50f, 0.50f},
        {0.27f, 0.59f, 0.27f},
        {0.55f, 0.35f, 0.15f},
        {0.70f, 0.30f, 0.20f},
        {0.90f, 0.80f, 0.10f},
    };
    String[] blockNames = {"DIRT", "STONE", "GRASS", "WOOD", "BRICK", "GOLD"};

    List<Box> world = new ArrayList<>();
    int chunkSize = 12;
    int renderDistance = 3;
    Map<String, Chunk> loadedChunks = new HashMap<>();
    Random rng = new Random(42);

    record Box(double x, double y, double z, double w, double h, double d, float r, float g, float b) {}
    record Chunk(int cx, int cz) {}

    int fontTex;
    int fontW = 512, fontH = 512;
    float[] charX = new float[256], charY = new float[256];
    float[] charW = new float[256], charH = new float[256];
    int[] charAdv = new int[256];
    ByteBuffer fontData;

    public static void main(String[] args) { new GpuBlockShot().run(); }

    void run() {
        generateFontData();
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(1100, 700, "BlockShot 3D — GPU Edition", 0, 0);
        if (window == 0) throw new IllegalStateException("Window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        uploadFontTexture();

        glfwSetKeyCallback(window, (w, key, sc, action, mods) -> {
            if (key >= 0 && key < keys.length) keys[key] = action != GLFW_RELEASE;
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) glfwSetWindowShouldClose(window, true);
            if (action == GLFW_PRESS) {
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_6)
                    selectedBlock = key - GLFW_KEY_1;
            }
        });
        double[] prevMX = new double[]{-1}, prevMY = new double[]{-1};
        glfwSetCursorPosCallback(window, (w, mx, my) -> {
            if (mouseCaptured && prevMX[0] >= 0) {
                yaw += (mx - prevMX[0]) * 0.15;
                pitch = Math.max(-70, Math.min(70, pitch + (my - prevMY[0]) * 0.12));
            }
            prevMX[0] = mx; prevMY[0] = my;
        });
        glfwSetMouseButtonCallback(window, (w, btn, act, mods) -> {
            if (!mouseCaptured) return;
            if (btn == GLFW_MOUSE_BUTTON_RIGHT && act == GLFW_PRESS) placeBlock();
        });
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
        glfwSetWindowFocusCallback(window, (w, focused) -> { mouseCaptured = focused; });
        mouseCaptured = true;

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_COLOR_MATERIAL);
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_NORMALIZE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        makeWorld();

        while (!glfwWindowShouldClose(window)) {
            update();
            draw();
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    double terrainNoise(double gx, double gz) {
        double h = 0;
        h += Math.sin(gx * 0.03 + gz * 0.04) * 4.5;
        h += Math.sin(gx * 0.07 - gz * 0.06 + 1.2) * 3.0;
        h += Math.sin(gx * 0.14 + gz * 0.11 + 0.8) * 1.8;
        h += Math.cos(gx * 0.10 - gz * 0.13) * 1.2;
        h += Math.sin(gx * 0.25 + gz * 0.22 + 2.1) * 0.6;
        h += Math.cos(gx * 0.30 - gz * 0.28 + 0.5) * 0.4;
        double dist = Math.sqrt(gx * gx + gz * gz);
        if (dist > 10) h += (dist - 10) * 0.3;
        if (dist < 6) h = Math.min(h, 1.0 + dist * 0.1);
        return Math.max(-1, h);
    }

    int getTerrainHeight(int gx, int gz) {
        double h = terrainNoise(gx, gz);
        h = Math.min(h, 8.5);
        return Math.max(0, (int) Math.round(h));
    }

    double getSurfaceY(int gx, int gz) {
        return getTerrainHeight(gx, gz) + 0.5;
    }

    void makeWorld() {
        world.clear();
        loadedChunks.clear();

        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                populateChunk(dx, dz);
            }
        }

        x = 0;
        y = 3.0;
        z = 0;
        yaw = 0;
        pitch = -8;
    }

    void ensureChunksAroundPlayer() {
        int chunkX = (int) Math.floor(x / chunkSize);
        int chunkZ = (int) Math.floor(z / chunkSize);
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                String key = (chunkX + dx) + ":" + (chunkZ + dz);
                if (!loadedChunks.containsKey(key)) {
                    populateChunk(chunkX + dx, chunkZ + dz);
                }
            }
        }
    }

    void populateChunk(int cx, int cz) {
        String key = cx + ":" + cz;
        if (loadedChunks.containsKey(key)) return;
        loadedChunks.put(key, new Chunk(cx, cz));

        int baseX = cx * chunkSize;
        int baseZ = cz * chunkSize;
        for (int gx = 0; gx < chunkSize; gx++) {
            for (int gz = 0; gz < chunkSize; gz++) {
                int worldX = baseX + gx;
                int worldZ = baseZ + gz;
                double h = terrainNoise(worldX, worldZ);
                h = Math.min(h, 8.5);
                boolean road = (Math.abs(worldX) < 2 && Math.abs(worldZ) < 24) || (Math.abs(worldZ) < 2 && Math.abs(worldX) < 24);
                int height = Math.max(0, (int) Math.round(road ? Math.max(0, h - 3) : h));
                if (road) {
                    height = 1;
                }
                for (int by = 0; by <= height; by++) {
                    float r, g, b;
                    boolean top = by == height;
                    if (road && top) {
                        r = 0.20f; g = 0.20f; b = 0.22f;
                    } else if (top) {
                        r = 0.20f + rng.nextFloat() * 0.08f; g = 0.48f + rng.nextFloat() * 0.12f; b = 0.14f + rng.nextFloat() * 0.08f;
                    } else if (by >= height - 1) {
                        r = 0.45f; g = 0.36f; b = 0.26f;
                    } else {
                        r = 0.36f; g = 0.31f; b = 0.26f;
                    }
                    world.add(new Box(worldX, by - 0.5, worldZ, 1, 1, 1, r, g, b));
                }
                if (height > 0 && rng.nextDouble() < 0.03 && !road && Math.abs(worldX) > 2 && Math.abs(worldZ) > 2) {
                    world.add(new Box(worldX + 0.15, height + 0.5, worldZ + 0.15, 0.12, 0.25, 0.12, 0.96f, 0.22f, 0.18f));
                }
                if (height <= 1 && rng.nextDouble() < 0.02 && Math.abs(worldX) > 3 && Math.abs(worldZ) > 3) {
                    world.add(new Box(worldX - 0.3, height + 0.5, worldZ - 0.3, 0.6, 0.4, 0.6, 0.5f, 0.5f, 0.52f));
                }
            }
        }

        if ((cx == 0 && cz == 0) || (cx == 0 && cz == -1) || (cx == 1 && cz == 0)) {
            house(2, -1, 0.82f, 0.64f, 0.38f, 0.67f, 0.27f, 0.19f);
            house(-4, 4, 0.59f, 0.74f, 0.81f, 0.27f, 0.35f, 0.60f);
            house(5, 8, 0.87f, 0.77f, 0.57f, 0.45f, 0.28f, 0.18f);
            villager(-2, 2, 0.96f, 0.70f, 0.34f);
            villager(3, 4, 0.38f, 0.69f, 0.95f);
            villager(1, 6, 0.94f, 0.43f, 0.60f);
        }
    }

    void house(double bx, double bz, float r, float g, float b, float rr, float rg, float rb) {
        int th = getTerrainHeight((int) Math.round(bx), (int) Math.round(bz));
        double gy = th - 0.5;
        world.add(new Box(bx, gy, bz, 3, 2.2, 3, r, g, b));
        world.add(new Box(bx - 0.22, gy + 2.2, bz - 0.22, 3.44, 0.7, 3.44, rr, rg, rb));
        world.add(new Box(bx + 1.15, gy, bz - 0.02, 0.05, 0.9, 0.65, 0.30f, 0.20f, 0.15f));
        world.add(new Box(bx + 0.28, gy + 1.12, bz - 0.03, 0.05, 0.55, 0.65, 0.42f, 0.80f, 0.91f));
    }

    void villager(double vx, double vz, float r, float g, float b) {
        int th = getTerrainHeight((int) Math.round(vx), (int) Math.round(vz));
        double gy = th - 0.5;
        world.add(new Box(vx - 0.25, gy, vz - 0.18, 0.5, 0.8, 0.36, 0.18f, 0.24f, 0.42f));
        world.add(new Box(vx - 0.30, gy + 0.8, vz - 0.22, 0.6, 0.72, 0.44, r, g, b));
        world.add(new Box(vx - 0.24, gy + 1.52, vz - 0.19, 0.48, 0.48, 0.38, 0.91f, 0.68f, 0.47f));
    }

    void update() {
        double s = keys[GLFW_KEY_LEFT_SHIFT] ? 0.12 : 0.075;
        double r = Math.toRadians(yaw);
        double fx = Math.sin(r), fz = Math.cos(r);
        double sx = Math.cos(r), sz = -Math.sin(r);
        if (keys[GLFW_KEY_W]) { x += fx * s; z += fz * s; }
        if (keys[GLFW_KEY_S]) { x -= fx * s; z -= fz * s; }
        if (keys[GLFW_KEY_A]) { x += sx * s; z += sz * s; }
        if (keys[GLFW_KEY_D]) { x -= sx * s; z -= sz * s; }
        ensureChunksAroundPlayer();
        int gx = (int) Math.round(x), gz = (int) Math.round(z);
        double sy = getSurfaceY(gx, gz);
        y = Math.max(sy + 0.15, 1.7);
    }

    double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    void placeBlock() {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double fx = Math.sin(yr) * Math.cos(pr);
        double fy = Math.sin(pr);
        double fz = Math.cos(yr) * Math.cos(pr);
        double dist = 3;
        double px = x + fx * dist;
        double py = y + fy * dist;
        double pz = z + fz * dist;
        px = Math.round(px);
        py = Math.round(py);
        pz = Math.round(pz);
        if (Math.hypot(px - x, pz - z) < 1.0 || Math.abs(py - y) < 0.5) return;
        for (Box b : world) {
            if (b.w() == 1 && b.h() == 1 && b.d() == 1 &&
                Math.abs(b.x() - px) < 0.1 && Math.abs(b.y() - py) < 0.1 && Math.abs(b.z() - pz) < 0.1)
                return;
        }
        float[] c = blockPalette[selectedBlock];
        float bright = (float) (0.7 + 0.3 * Math.random());
        world.add(new Box(px, py, pz, 1, 1, 1, c[0] * bright, c[1] * bright, c[2] * bright));
    }

    void draw() {
        int[] ww = new int[1], hh = new int[1];
        glfwGetFramebufferSize(window, ww, hh);
        int W = ww[0], H = hh[0];
        glViewport(0, 0, W, H);
        glClearColor(0.45f, 0.70f, 0.90f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        double aspect = (double) W / H, n = 0.1, f = 100;
        double t = Math.tan(Math.toRadians(68) / 2) * n;
        glFrustum(-t * aspect, t * aspect, -t, t, n, f);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glRotated(pitch, 1, 0, 0);
        glRotated(yaw + 180, 0, 1, 0);
        glTranslated(-x, -y, -z);

        float[] sunPos = {-0.4f, 1.3f, 0.3f, 0};
        float[] sunDiff = {1f, 1f, 0.92f, 1};
        float[] sunAmb = {0.20f, 0.22f, 0.25f, 1};
        glLightModelfv(GL_LIGHT_MODEL_AMBIENT, sunAmb);
        glLightfv(GL_LIGHT0, GL_POSITION, sunPos);
        glLightfv(GL_LIGHT0, GL_DIFFUSE, sunDiff);

        for (Box b : world) drawBox(b);
        drawArm();

        drawHUD(W, H);
    }

    void drawBox(Box b) {
        glColor3f(b.r(), b.g(), b.b());
        glPushMatrix();
        glTranslated(b.x() + b.w() / 2, b.y() + b.h() / 2, b.z() + b.d() / 2);
        glScaled(b.w(), b.h(), b.d());
        glBegin(GL_QUADS);
        face(0, 0, 1,   -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1);
        face(0, 0, -1,   1, -1, -1, -1, -1, -1, -1, 1, -1, 1, 1, -1);
        face(1, 0, 0,   1, -1, 1, 1, -1, -1, 1, 1, -1, 1, 1, 1);
        face(-1, 0, 0,  -1, -1, -1, -1, -1, 1, -1, 1, 1, -1, 1, -1);
        face(0, 1, 0,   -1, 1, 1, 1, 1, 1, 1, 1, -1, -1, 1, -1);
        face(0, -1, 0,  -1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1);
        glEnd();
        glPopMatrix();
    }

    void drawArm() {
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glTranslated(0.55, -0.35, -0.7);
        float[] c = blockPalette[selectedBlock];
        glColor3f(c[0] * 0.8f, c[1] * 0.8f, c[2] * 0.8f);
        glBegin(GL_QUADS);
        face(0, 0, 1,   -0.12, -0.15, 0, 0.12, -0.15, 0, 0.12, 0.15, 0, -0.12, 0.15, 0);
        face(0, 0, -1,  0.12, -0.15, -0.15, -0.12, -0.15, -0.15, -0.12, 0.15, -0.15, 0.12, 0.15, -0.15);
        face(1, 0, 0,   0.12, -0.15, -0.15, 0.12, -0.15, 0, 0.12, 0.15, 0, 0.12, 0.15, -0.15);
        face(-1, 0, 0,  -0.12, -0.15, 0, -0.12, -0.15, -0.15, -0.12, 0.15, -0.15, -0.12, 0.15, 0);
        face(0, 1, 0,   -0.12, 0.15, 0, 0.12, 0.15, 0, 0.12, 0.15, -0.15, -0.12, 0.15, -0.15);
        face(0, -1, 0,  -0.12, -0.15, -0.15, 0.12, -0.15, -0.15, 0.12, -0.15, 0, -0.12, -0.15, 0);
        glEnd();
        glColor3f(0.85f, 0.65f, 0.45f);
        glBegin(GL_QUADS);
        face(0, 0, 1,   -0.10, -0.35, -0.16, 0.10, -0.35, -0.16, 0.10, -0.15, -0.16, -0.10, -0.15, -0.16);
        face(0, 0, -1,  0.10, -0.35, -0.30, -0.10, -0.35, -0.30, -0.10, -0.15, -0.30, 0.10, -0.15, -0.30);
        face(1, 0, 0,   0.10, -0.35, -0.30, 0.10, -0.35, -0.16, 0.10, -0.15, -0.16, 0.10, -0.15, -0.30);
        face(-1, 0, 0,  -0.10, -0.35, -0.16, -0.10, -0.35, -0.30, -0.10, -0.15, -0.30, -0.10, -0.15, -0.16);
        face(0, 1, 0,   -0.10, -0.15, -0.16, 0.10, -0.15, -0.16, 0.10, -0.15, -0.30, -0.10, -0.15, -0.30);
        face(0, -1, 0,  -0.10, -0.35, -0.30, 0.10, -0.35, -0.30, 0.10, -0.35, -0.16, -0.10, -0.35, -0.16);
        glEnd();
        glPopMatrix();
    }

    void face(double nx, double ny, double nz, double... v) {
        glNormal3d(nx, ny, nz);
        for (int i = 0; i < v.length; i += 3) glVertex3d(v[i], v[i + 1], v[i + 2]);
    }

    void drawHUD(int W, int H) {
        glDisable(GL_LIGHTING);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0, W, H, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        glColor4f(0.03f, 0.06f, 0.09f, 0.82f);
        glBegin(GL_QUADS);
        glVertex2i(16, 15); glVertex2i(336, 15);
        glVertex2i(336, 90); glVertex2i(16, 90);
        glEnd();
        glBegin(GL_QUADS);
        glVertex2i(W - 296, 15); glVertex2i(W - 16, 15);
        glVertex2i(W - 16, 90); glVertex2i(W - 296, 90);
        glEnd();

        glColor3f(1, 0.9f, 0.4f);
        glBegin(GL_QUADS);
        glVertex2i(W - 120, 20); glVertex2i(W - 60, 20);
        glVertex2i(W - 60, 80); glVertex2i(W - 120, 80);
        glEnd();
        glColor3f(1f, 0.95f, 0.6f);
        drawString("☼", W - 114, 30, 0.42);

        glColor3f(1, 1, 1);
        drawString("BLOCKSHOT 3D - EXPLORATION MODE", 28, 26, 0.45);
        glColor3f(0.75f, 0.75f, 0.75f);
        drawString("SCORE " + score, 28, 58, 0.35);


        drawPalette(W, H);

        glColor3f(1, 1, 1);
        glLineWidth(2f);
        glBegin(GL_LINES);
        glVertex2i(W / 2 - 12, H / 2); glVertex2i(W / 2 - 5, H / 2);
        glVertex2i(W / 2 + 5, H / 2); glVertex2i(W / 2 + 12, H / 2);
        glVertex2i(W / 2, H / 2 - 12); glVertex2i(W / 2, H / 2 - 5);
        glVertex2i(W / 2, H / 2 + 5); glVertex2i(W / 2, H / 2 + 12);
        glEnd();
        glColor4f(1, 1, 1, 0.4f);
        glBegin(GL_LINES);
        glVertex2i(W / 2 - 5, H / 2); glVertex2i(W / 2 + 5, H / 2);
        glVertex2i(W / 2, H / 2 - 5); glVertex2i(W / 2, H / 2 + 5);
        glEnd();

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);
    }

    void drawPalette(int W, int H) {
        int boxSize = 36, gap = 6, startX = (W - (boxSize + gap) * 6 + gap) / 2, startY = H - 50;
        for (int i = 0; i < 6; i++) {
            int bx = startX + i * (boxSize + gap);
            float[] c = blockPalette[i];
            if (i == selectedBlock) {
                glColor3f(1, 1, 1);
                glBegin(GL_QUADS);
                glVertex2i(bx - 2, startY - 2);
                glVertex2i(bx + boxSize + 2, startY - 2);
                glVertex2i(bx + boxSize + 2, startY + boxSize + 2);
                glVertex2i(bx - 2, startY + boxSize + 2);
                glEnd();
            }
            glColor3f(c[0], c[1], c[2]);
            glBegin(GL_QUADS);
            glVertex2i(bx, startY);
            glVertex2i(bx + boxSize, startY);
            glVertex2i(bx + boxSize, startY + boxSize);
            glVertex2i(bx, startY + boxSize);
            glEnd();
            if (i == selectedBlock) {
                glColor3f(0, 0, 0);
                glBegin(GL_LINE_LOOP);
                glVertex2i(bx + 3, startY + 3);
                glVertex2i(bx + boxSize - 3, startY + 3);
                glVertex2i(bx + boxSize - 3, startY + boxSize - 3);
                glVertex2i(bx + 3, startY + boxSize - 3);
                glEnd();
            }
        }
        glColor3f(0.75f, 0.75f, 0.75f);
        drawString(blockNames[selectedBlock], startX, startY - 18, 0.30);
    }

    void drawString(String s, double x, double y, double scale) {
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, fontTex);
        glBegin(GL_QUADS);
        double cx = x;
        float fw = fontW, fh = fontH;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c < 32 || c > 126) { cx += 12 * scale; continue; }
            float tw = charW[c] * (float) scale;
            float th = charH[c] * (float) scale;
            float tx = charX[c] / fw;
            float ty = (charY[c] + charH[c]) / fh;
            float tx2 = (charX[c] + charW[c]) / fw;
            float ty2 = charY[c] / fh;
            glTexCoord2f(tx, ty); glVertex2d(cx, y + th);
            glTexCoord2f(tx2, ty); glVertex2d(cx + tw, y + th);
            glTexCoord2f(tx2, ty2); glVertex2d(cx + tw, y);
            glTexCoord2f(tx, ty2); glVertex2d(cx, y);
            cx += charAdv[c] * scale;
        }
        glEnd();
        glDisable(GL_TEXTURE_2D);
    }

    void generateFontData() {
        BufferedImage img = new BufferedImage(fontW, fontH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = new Font(Font.MONOSPACED, Font.BOLD, 44);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int cx = 4, cy = 4, rowH = 0;
        for (int i = 32; i < 127; i++) {
            int cw = fm.charWidth(i);
            int ch = fm.getAscent() + fm.getDescent();
            if (cx + cw + 4 > fontW) { cx = 4; cy += rowH + 4; rowH = 0; }
            charX[i] = cx; charY[i] = cy;
            charW[i] = cw; charH[i] = ch;
            charAdv[i] = fm.charWidth(i);
            g.setColor(Color.WHITE);
            g.drawString(String.valueOf((char) i), cx, cy + fm.getAscent());
            cx += cw + 4;
            rowH = Math.max(rowH, ch);
        }
        g.dispose();
        fontData = BufferUtils.createByteBuffer(fontW * fontH * 4);
        for (int y = 0; y < fontH; y++)
            for (int x = 0; x < fontW; x++) {
                int argb = img.getRGB(x, y);
                fontData.put((byte) ((argb >> 16) & 0xFF));
                fontData.put((byte) ((argb >> 8) & 0xFF));
                fontData.put((byte) (argb & 0xFF));
                fontData.put((byte) ((argb >> 24) & 0xFF));
            }
        fontData.flip();
    }

    void uploadFontTexture() {
        fontTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fontTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, fontW, fontH, 0, GL_RGBA, GL_UNSIGNED_BYTE, fontData);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
}
