package com.blockshot.render;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glColor4f;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glMatrixMode;
import static org.lwjgl.opengl.GL11.glNormal3d;
import static org.lwjgl.opengl.GL11.glPopMatrix;
import static org.lwjgl.opengl.GL11.glPushMatrix;
import static org.lwjgl.opengl.GL11.glRotated;
import static org.lwjgl.opengl.GL11.glTranslated;
import static org.lwjgl.opengl.GL11.glVertex3d;
import static org.lwjgl.opengl.GL11.GL_MODELVIEW;

import com.blockshot.entity.Fish;
import com.blockshot.entity.Npc;
import com.blockshot.entity.NpcRole;
import com.blockshot.entity.NpcState;
import com.blockshot.entity.Vehicle;
import com.blockshot.entity.Villager;
import com.blockshot.net.NetworkMessage;
import com.blockshot.physics.FallingBlockEntity;
import com.blockshot.world.BlockMaterial;
import com.blockshot.world.Box;
import java.util.UUID;

/** Immediate renderer reserved for small moving objects; terrain uses cached chunk VBOs. */
public final class EntityRenderer {

    public void drawBox(Box box) {
        double x0 = box.x(), y0 = box.y(), z0 = box.z();
        double x1 = x0 + box.w(), y1 = y0 + box.h(), z1 = z0 + box.d();
        glColor4f(box.r(), box.g(), box.b(), box.a());
        glBegin(GL_QUADS);
        face(0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
        face(1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        face(-1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
        face(0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        glEnd();
    }

    public void drawVillager(Villager villager) {
        drawPerson(villager.x, villager.y, villager.z,
                villager.r, villager.g, villager.b, false, false);
    }

    public void drawNpc(Npc npc) {
        if (npc.state() == NpcState.DEAD) {
            drawBox(new Box(npc.x - 0.75, npc.y + 0.08, npc.z - 0.22,
                    1.5, 0.36, 0.44, 0.38f, 0.18f, 0.18f));
            return;
        }
        boolean police = npc.role() == NpcRole.POLICE;
        int shade = npc.id().hashCode();
        float r = police ? 0.10f : 0.35f + ((shade >>> 16) & 0x7F) / 500f;
        float g = police ? 0.23f : 0.30f + ((shade >>> 8) & 0x7F) / 500f;
        float b = police ? 0.62f : 0.32f + (shade & 0x7F) / 500f;
        drawPerson(npc.x, npc.y, npc.z, r, g, b, police, npc.state() == NpcState.PURSUE);
    }

    public void drawRemotePlayer(NetworkMessage.PlayerState state) {
        drawPerson(state.x(), state.y(), state.z(), 0.18f, 0.74f, 0.88f, false, false);
    }

    public void drawFish(Fish fish) {
        glPushMatrix();
        glTranslated(fish.x, fish.y, fish.z);
        glRotated(fish.facing, 0, 1, 0);
        drawBox(new Box(-0.24, -0.08, -0.10, 0.48, 0.20, 0.28,
                fish.r, fish.g, fish.b, 0.95f));
        drawBox(new Box(-0.08, -0.04, -0.28, 0.16, 0.14, 0.20,
                fish.r * 0.8f, fish.g * 0.8f, fish.b * 0.8f, 0.95f));
        glPopMatrix();
    }

    public void drawVehicle(Vehicle vehicle) {
        glPushMatrix();
        glTranslated(vehicle.x, vehicle.y, vehicle.z);
        glRotated(vehicle.yaw, 0, 1, 0);
        glRotated(vehicle.pitch, 1, 0, 0);
        glRotated(vehicle.roll, 0, 0, 1);
        switch (vehicle.type) {
            case CAR -> drawCar();
            case HELICOPTER -> drawHelicopter();
            case PLANE -> drawPlane();
        }
        glPopMatrix();
    }

    public void drawFallingBlock(FallingBlockEntity block) {
        BlockMaterial material = block.material();
        drawBox(new Box(block.x(), block.y(), block.z(), 1, 1, 1,
                material.r(), material.g(), material.b(), material.a()));
    }

    public void drawHeldItem(BlockMaterial material, boolean gun) {
        glClear(GL_DEPTH_BUFFER_BIT);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        if (gun) {
            drawBox(new Box(0.30, -0.30, -0.95, 0.22, 0.18, 0.58,
                    0.18f, 0.20f, 0.23f));
            drawBox(new Box(0.34, -0.48, -0.60, 0.16, 0.24, 0.16,
                    0.11f, 0.12f, 0.14f));
            drawBox(new Box(0.36, -0.25, -1.28, 0.10, 0.10, 0.36,
                    0.30f, 0.32f, 0.34f));
        } else {
            drawBox(new Box(0.38, -0.30, -0.92, 0.25, 0.30, 0.30,
                    material.r() * 0.85f, material.g() * 0.85f,
                    material.b() * 0.85f, 1));
        }
        drawBox(new Box(0.38, -0.52, -0.60, 0.20, 0.24, 0.20,
                0.85f, 0.65f, 0.45f));
        glPopMatrix();
    }

    private void drawPerson(double x, double y, double z, float r, float g, float b,
                            boolean police, boolean pursuing) {
        drawBox(new Box(x - 0.22, y, z - 0.17, 0.20, 0.78, 0.32,
                0.15f, 0.18f, 0.25f));
        drawBox(new Box(x + 0.02, y, z - 0.17, 0.20, 0.78, 0.32,
                0.15f, 0.18f, 0.25f));
        drawBox(new Box(x - 0.30, y + 0.78, z - 0.22, 0.60, 0.72, 0.44, r, g, b));
        drawBox(new Box(x - 0.24, y + 1.50, z - 0.19, 0.48, 0.48, 0.38,
                0.91f, 0.68f, 0.47f));
        if (police) {
            drawBox(new Box(x - 0.27, y + 1.92, z - 0.22, 0.54, 0.12, 0.44,
                    0.04f, 0.08f, 0.18f));
            if (pursuing) {
                drawBox(new Box(x - 0.17, y + 1.55, z - 0.25, 0.34, 0.12, 0.08,
                        0.86f, 0.74f, 0.12f));
            }
        }
    }

    private void drawCar() {
        drawBox(new Box(-0.95, 0.20, -1.75, 1.90, 0.65, 3.50,
                0.72f, 0.12f, 0.10f));
        drawBox(new Box(-0.72, 0.85, -0.75, 1.44, 0.62, 1.65,
                0.22f, 0.38f, 0.52f, 0.85f));
        for (double x : new double[] {-1.02, 0.80}) {
            for (double z : new double[] {-1.20, 0.90}) {
                drawBox(new Box(x, 0.05, z, 0.22, 0.55, 0.62,
                        0.06f, 0.06f, 0.07f));
            }
        }
    }

    private void drawHelicopter() {
        drawBox(new Box(-0.82, 0.42, -1.25, 1.64, 1.18, 2.50,
                0.16f, 0.48f, 0.24f));
        drawBox(new Box(-0.68, 0.72, 0.80, 1.36, 0.62, 1.20,
                0.28f, 0.48f, 0.58f, 0.78f));
        drawBox(new Box(-0.18, 0.84, -4.25, 0.36, 0.38, 3.20,
                0.14f, 0.38f, 0.20f));
        drawBox(new Box(-3.80, 1.78, -0.12, 7.60, 0.08, 0.24,
                0.12f, 0.12f, 0.13f));
        drawBox(new Box(-0.12, 1.76, -3.80, 0.24, 0.08, 7.60,
                0.12f, 0.12f, 0.13f));
        drawBox(new Box(-0.08, 0.25, -4.55, 0.16, 1.20, 0.16,
                0.12f, 0.12f, 0.13f));
    }

    private void drawPlane() {
        drawBox(new Box(-0.72, 0.45, -4.80, 1.44, 1.35, 9.60,
                0.84f, 0.86f, 0.90f));
        drawBox(new Box(-5.20, 0.90, -0.55, 10.40, 0.18, 2.20,
                0.78f, 0.80f, 0.84f));
        drawBox(new Box(-2.20, 1.00, -4.45, 4.40, 0.16, 1.55,
                0.72f, 0.75f, 0.80f));
        drawBox(new Box(-0.14, 1.55, -4.35, 0.28, 1.65, 1.60,
                0.22f, 0.42f, 0.72f));
        drawBox(new Box(-0.58, 0.88, 3.90, 1.16, 0.55, 0.55,
                0.20f, 0.42f, 0.62f, 0.82f));
    }

    private static void face(double nx, double ny, double nz, double... vertices) {
        glNormal3d(nx, ny, nz);
        for (int index = 0; index < vertices.length; index += 3) {
            glVertex3d(vertices[index], vertices[index + 1], vertices[index + 2]);
        }
    }
}