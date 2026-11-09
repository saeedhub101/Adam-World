package com.explorer.world3d;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads Adam World Mesh (AWM1), a compact runtime format generated during the
 * Android build from individually licensed source meshes. No GLB parser or
 * GLB asset is shipped in the application.
 */
final class AssetMeshLoader {
    private static final int MAGIC_V1 = 0x314d5741; // "AWM1" in little endian
    private static final int MAGIC_V2 = 0x324d5741; // "AWM2" in little endian
    private static final int FLOATS_PER_VERTEX_V1 = 9; // position, normal, RGB
    private static final int FLOATS_PER_VERTEX_V2 = 11; // position, normal, UV, RGB
    private AssetMeshLoader() {}

    static Mesh load(Context context, String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[32768];
            int read;
            while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
            byte[] raw = out.toByteArray();
            if (raw.length < 8) return null;
            ByteBuffer data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
             int magic = data.getInt();
            int floatsPerVertex = magic == MAGIC_V2 ? FLOATS_PER_VERTEX_V2
                    : magic == MAGIC_V1 ? FLOATS_PER_VERTEX_V1 : 0;
            if (floatsPerVertex == 0) return null;
            int vertices = data.getInt();
            if (vertices < 3 || vertices > 1_500_000
                    || data.remaining() != vertices * floatsPerVertex * 4) return null;
            GeoBuf mesh = new GeoBuf();
            for (int i = 0; i < vertices; i++) {
                float x = data.getFloat(), y = data.getFloat(), z = data.getFloat();
                float nx = data.getFloat(), ny = data.getFloat(), nz = data.getFloat();
                float u = floatsPerVertex == FLOATS_PER_VERTEX_V2 ? data.getFloat() : .5f;
                float v = floatsPerVertex == FLOATS_PER_VERTEX_V2 ? data.getFloat() : .5f;
                float r = data.getFloat(), g = data.getFloat(), b = data.getFloat();
                mesh.vn(x, y, z, nx, ny, nz, u, v, r, g, b);
            }
            return Mesh.from(mesh);
        } catch (Exception ignored) {
            // A missing or invalid optional asset must never prevent the game starting.
            return null;
        }
    }
}
