package com.blockshot.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

import com.blockshot.entity.Npc;
import com.blockshot.entity.NpcRole;
import com.blockshot.entity.Player;
import com.blockshot.entity.Villager;
import com.blockshot.world.Chunk;
import com.blockshot.world.ChunkManager;
import com.blockshot.world.TerrainGenerator;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;

/**
 * Draws the 2D overlay: title, coordinates, the block palette, a crosshair and a
 * top-down minimap. Owns its own bitmap font texture so the main game class does
 * not have to deal with text rendering.
 */
public final class HudRenderer implements AutoCloseable {

    private int fontTex;
    private final int fontW = 512, fontH = 512;
    private final float[] charX = new float[256], charY = new float[256];
    private final float[] charW = new float[256], charH = new float[256];
    private final int[] charAdv = new int[256];
    private ByteBuffer fontData;

    public void init() {
        if (fontTex != 0) return;
        generateFontData();
        uploadFontTexture();
    }

    public void render(int W, int H, float[][] palette, String[] names, int selected,
                       Player player, ChunkManager chunks) {
        render(W, H, palette, names, selected, player, chunks, HudState.exploration());
    }

    public void render(int W, int H, float[][] palette, String[] names, int selected,
                       Player player, ChunkManager chunks, HudState state) {
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

        // Info panel.
        glColor4f(0.03f, 0.06f, 0.09f, 0.82f);
        quad(16, 15, 500, 150);

        glColor3f(1, 1, 1);
        drawString("BLOCKSHOT 3D - OPEN WORLD", 28, 26, 0.42);
        glColor3f(0.78f, 0.82f, 0.86f);
        drawString(String.format("X %.1f  Y %.1f  Z %.1f", player.x, player.y, player.z), 28, 54, 0.30);
        drawString("CHUNKS " + chunks.loadedCount() + "  FACING " + heading(player.yaw)
                + "  GPU " + formatMegabytes(state.gpuBytes()) + "MB"
                + "  DRAWS " + state.drawCalls(), 28, 76, 0.27);
        glColor3f(state.health() > 30 ? 0.45f : 1f, state.health() > 30 ? 0.95f : 0.25f, 0.35f);
        drawString("HP " + state.health() + "  WANTED " + stars(state.wantedLevel())
                + "  MODE " + state.mode(), 28, 98, 0.30);
        glColor3f(0.86f, 0.88f, 0.92f);
        drawString("WEAPON " + state.weapon() + " " + state.ammo() + "/" + state.reserve()
                + "  BLOCKS " + state.selectedBlockCount(), 28, 120, 0.27);
        drawString(state.vehicle() + " " + state.occupants() + "/" + state.vehicleCapacity()
                + "  NET " + state.network() + "  PLAYERS " + state.remotePlayers(),
                28, 140, 0.25);

        if (!state.message().isBlank()) {
            glColor4f(0.03f, 0.06f, 0.09f, 0.82f);
            quad(W / 2.0 - 260, H - 98, W / 2.0 + 260, H - 70);
            glColor3f(1f, 0.9f, 0.45f);
            drawString(state.message(), W / 2.0 - 248, H - 91, 0.25);
        }

        drawPalette(W, H, palette, names, selected);
        drawMinimap(W, H, player, chunks);
        drawCrosshair(W, H);

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);
    }

    private static String heading(double yaw) {
        double y = ((yaw % 360) + 360) % 360;
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.round(y / 45) % 8];
    }

    private static String stars(int wanted) {
        return "*".repeat(wanted) + "-".repeat(5 - wanted);
    }

    private static String formatMegabytes(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private void drawMinimap(int W, int H, Player player, ChunkManager chunks) {
        int size = 168, pad = 16;
        int ox = W - size - pad, oy = pad;
        int range = 42;            // blocks shown from centre to edge
        double scale = size / (2.0 * range);

        glColor4f(0.05f, 0.08f, 0.11f, 0.85f);
        quad(ox - 3, oy - 3, ox + size + 3, oy + size + 3);

        // Terrain / city colours sampled on a coarse grid.
        int step = 3;
        int pcx = (int) Math.floor(player.x), pcz = (int) Math.floor(player.z);
        glBegin(GL_QUADS);
        for (int dz = -range; dz < range; dz += step) {
            for (int dx = -range; dx < range; dx += step) {
                int wx = pcx + dx, wz = pcz + dz;
                int h = chunks.columnHeight(wx, wz);
                float[] col = minimapColor(chunks, wx, wz, h);
                glColor3f(col[0], col[1], col[2]);
                double sx = ox + (dx + range) * scale;
                double sy = oy + (dz + range) * scale;
                double s = step * scale + 1;
                glVertex2d(sx, sy);
                glVertex2d(sx + s, sy);
                glVertex2d(sx + s, sy + s);
                glVertex2d(sx, sy + s);
            }
        }
        glEnd();

        // Villager dots.
        glColor3f(1f, 0.9f, 0.2f);
        glBegin(GL_QUADS);
        for (Chunk c : chunks.loadedChunks()) {
            for (Villager v : c.villagers) {
                double dx = v.x - player.x, dz = v.z - player.z;
                if (Math.abs(dx) > range || Math.abs(dz) > range) continue;
                double sx = ox + (dx + range) * scale;
                double sy = oy + (dz + range) * scale;
                glVertex2d(sx - 1.5, sy - 1.5);
                glVertex2d(sx + 1.5, sy - 1.5);
                glVertex2d(sx + 1.5, sy + 1.5);
                glVertex2d(sx - 1.5, sy + 1.5);
            }
        }
        glEnd();

        // GTA-style crowd markers: civilians are yellow, police are blue.
        glBegin(GL_QUADS);
        for (Chunk c : chunks.loadedChunks()) {
            for (Npc npc : c.npcs) {
                if (!npc.alive()) continue;
                double dx = npc.x - player.x, dz = npc.z - player.z;
                if (Math.abs(dx) > range || Math.abs(dz) > range) continue;
                if (npc.role() == NpcRole.POLICE) glColor3f(0.2f, 0.5f, 1f);
                else glColor3f(1f, 0.82f, 0.18f);
                double sx = ox + (dx + range) * scale;
                double sy = oy + (dz + range) * scale;
                glVertex2d(sx - 1.7, sy - 1.7);
                glVertex2d(sx + 1.7, sy - 1.7);
                glVertex2d(sx + 1.7, sy + 1.7);
                glVertex2d(sx - 1.7, sy + 1.7);
            }
        }
        glEnd();

        // Player marker at the centre.
        glColor3f(1f, 0.25f, 0.25f);
        double cx = ox + size / 2.0, cy = oy + size / 2.0;
        glBegin(GL_QUADS);
        glVertex2d(cx - 2.5, cy - 2.5);
        glVertex2d(cx + 2.5, cy - 2.5);
        glVertex2d(cx + 2.5, cy + 2.5);
        glVertex2d(cx - 2.5, cy + 2.5);
        glEnd();
    }

    private float[] minimapColor(ChunkManager chunks, int wx, int wz, int h) {
        if (h <= chunks.waterLevel()) return new float[]{0.16f, 0.34f, 0.58f};
        TerrainGenerator.Biome biome = chunks.terrain().biomeAt(wx, wz);
        float shade = 0.6f + Math.min(0.4f, (h - chunks.waterLevel()) * 0.03f);
        switch (biome) {
            case HILLS: return new float[]{0.5f * shade, 0.5f * shade, 0.52f * shade};
            case FOREST: return new float[]{0.15f * shade, 0.45f * shade, 0.18f * shade};
            default: return new float[]{0.3f * shade, 0.55f * shade, 0.25f * shade};
        }
    }

    private void drawPalette(int W, int H, float[][] palette, String[] names, int selected) {
        int count = Math.min(palette.length, names.length);
        int boxSize = count > 9 ? 28 : 36;
        int gap = 5;
        int startX = (W - (boxSize + gap) * count + gap) / 2, startY = H - 44;
        for (int i = 0; i < count; i++) {
            int bx = startX + i * (boxSize + gap);
            float[] c = palette[i];
            if (i == selected) {
                glColor3f(1, 1, 1);
                quad(bx - 2, startY - 2, bx + boxSize + 2, startY + boxSize + 2);
            }
            glColor3f(c[0], c[1], c[2]);
            quad(bx, startY, bx + boxSize, startY + boxSize);
        }
        glColor3f(0.8f, 0.8f, 0.8f);
        drawString(names[selected], startX, startY - 18, 0.30);
    }

    private void drawCrosshair(int W, int H) {
        int cx = W / 2;
        int cy = H / 2;
        glColor3f(0, 0, 0);
        glLineWidth(5f);
        drawCrosshairLines(cx, cy, 13, 4);
        glColor3f(1, 1, 1);
        glLineWidth(2f);
        drawCrosshairLines(cx, cy, 12, 5);
        glPointSize(5f);
        glBegin(GL_POINTS);
        glVertex2i(cx, cy);
        glEnd();
    }

    private void drawCrosshairLines(int cx, int cy, int outer, int inner) {
        glBegin(GL_LINES);
        glVertex2i(cx - outer, cy); glVertex2i(cx - inner, cy);
        glVertex2i(cx + inner, cy); glVertex2i(cx + outer, cy);
        glVertex2i(cx, cy - outer); glVertex2i(cx, cy - inner);
        glVertex2i(cx, cy + inner); glVertex2i(cx, cy + outer);
        glEnd();
    }

    private void quad(double x0, double y0, double x1, double y1) {
        glBegin(GL_QUADS);
        glVertex2d(x0, y0);
        glVertex2d(x1, y0);
        glVertex2d(x1, y1);
        glVertex2d(x0, y1);
        glEnd();
    }

    private void drawString(String s, double x, double y, double scale) {
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

    private void generateFontData() {
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

    private void uploadFontTexture() {
        fontTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fontTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, fontW, fontH, 0, GL_RGBA, GL_UNSIGNED_BYTE, fontData);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        fontData = null;
    }

    @Override
    public void close() {
        if (fontTex != 0) {
            glDeleteTextures(fontTex);
            fontTex = 0;
        }
        fontData = null;
    }
}
